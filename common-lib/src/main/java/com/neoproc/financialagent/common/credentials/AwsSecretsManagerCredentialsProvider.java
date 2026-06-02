package com.neoproc.financialagent.common.credentials;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

/**
 * Production {@link CredentialsProvider} backed by AWS Secrets Manager.
 *
 * <p>Secret paths follow the convention from CONTRACT.md and
 * PraxisIntegrationHandoff.md §15.2, optionally prefixed by
 * {@code FINANCEAGENT_SECRETS_ENV_PREFIX} (e.g. {@code prod}):
 * <ul>
 *   <li>Per-firm:    {@code [prefix/]financeagent/firms/<firmId>/portals/<portalId>}</li>
 *   <li>Shared:      {@code [prefix/]financeagent/shared/portals/<portalId>}</li>
 *   <li>Per-client:  {@code [prefix/]financeagent/firms/<firmId>/portals/<portalId>/<clientId>}</li>
 * </ul>
 *
 * <p>The secret value must be a JSON object whose keys map directly to
 * {@link PortalCredentials} values (e.g. {@code {"username":"…","password":"…"}}).
 *
 * <p>The {@code credentialScopeFor} resolver determines per-portal whether to
 * use the shared, per-firm, or per-client path. Callers supply this as a
 * lambda so that this class stays independent of the descriptor loader in
 * {@code agent-worker}. In practice: {@code id -> descriptor.credentialScope()}.
 *
 * <p>Select this provider at runtime with {@code FINANCEAGENT_CREDENTIALS=aws}.
 */
public final class AwsSecretsManagerCredentialsProvider implements CredentialsProvider {

    private static final String SCOPE_SHARED = "shared";
    private static final String SCOPE_PER_CLIENT = "per-client";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final String firmId;
    private final Function<String, String> credentialScopeFor;
    private final SecretsManagerClient secretsManager;
    private final String envPrefix;

    public AwsSecretsManagerCredentialsProvider(String firmId,
                                                Function<String, String> credentialScopeFor) {
        this(firmId, credentialScopeFor,
             normalizePrefix(System.getenv("FINANCEAGENT_SECRETS_ENV_PREFIX")),
             SecretsManagerClient.create());
    }

    AwsSecretsManagerCredentialsProvider(String firmId,
                                         Function<String, String> credentialScopeFor,
                                         SecretsManagerClient secretsManager) {
        this(firmId, credentialScopeFor, "", secretsManager);
    }

    AwsSecretsManagerCredentialsProvider(String firmId,
                                         Function<String, String> credentialScopeFor,
                                         String envPrefix,
                                         SecretsManagerClient secretsManager) {
        this.firmId = firmId;
        this.credentialScopeFor = credentialScopeFor;
        this.envPrefix = normalizePrefix(envPrefix);
        this.secretsManager = secretsManager;
    }

    private static String normalizePrefix(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return raw.endsWith("/") ? raw : raw + "/";
    }

    @Override
    public PortalCredentials get(String portalId, String clientId) {
        String scope = credentialScopeFor.apply(portalId);
        String secretId = resolveSecretId(scope, portalId, clientId);

        String secretJson = secretsManager.getSecretValue(
                GetSecretValueRequest.builder().secretId(secretId).build()
        ).secretString();

        if (secretJson == null || secretJson.isBlank()) {
            throw new IllegalStateException(
                    "AWS Secrets Manager returned an empty secret for: " + secretId);
        }

        try {
            Map<String, String> values = MAPPER.readValue(secretJson, MAP_TYPE);
            return new PortalCredentials(portalId, Map.copyOf(values));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Secret at " + secretId + " is not a valid JSON key-value map", e);
        }
    }

    private String resolveSecretId(String scope, String portalId, String clientId) {
        if (SCOPE_SHARED.equals(scope)) {
            return envPrefix + "financeagent/shared/portals/" + portalId;
        }
        if (SCOPE_PER_CLIENT.equals(scope)) {
            // Verbatim segment — NO normalization. The Praxis contract
            // (PraxisIntegrationHandoff.md §15.2) requires byte-for-byte equality
            // between task.clientIdentifier and the <corporateId> path segment;
            // dashes, casing and whitespace are all significant. Praxis is the
            // single canonical writer of both the envelope value and the secret
            // name (trimmed once at input time), so they always agree.
            //
            // Dev's LocalFileCredentialsProvider MAY still normalize for
            // developer convenience — it sits outside this Praxis↔Secrets-Manager
            // contract and Praxis never writes secrets.properties.
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalStateException(
                        "per-client portal '" + portalId + "' requires a clientIdentifier "
                                + "but the request envelope did not provide one");
            }
            return envPrefix + "financeagent/firms/" + firmId + "/portals/" + portalId + "/" + clientId;
        }
        return envPrefix + "financeagent/firms/" + firmId + "/portals/" + portalId;
    }
}
