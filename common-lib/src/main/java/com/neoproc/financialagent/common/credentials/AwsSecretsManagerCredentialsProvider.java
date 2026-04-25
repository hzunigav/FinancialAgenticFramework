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
 * <p>Secret paths follow the convention from CONTRACT.md:
 * <ul>
 *   <li>Per-firm: {@code financeagent/firms/<firmId>/portals/<portalId>}</li>
 *   <li>Shared:   {@code financeagent/shared/portals/<portalId>}</li>
 * </ul>
 *
 * <p>The secret value must be a JSON object whose keys map directly to
 * {@link PortalCredentials} values (e.g. {@code {"username":"…","password":"…"}}).
 *
 * <p>The {@code credentialScopeFor} resolver determines per-portal whether to
 * use the shared or per-firm path. Callers supply this as a lambda so that
 * this class stays independent of the descriptor loader in {@code agent-worker}.
 * In practice: {@code id -> descriptor.credentialScope()}.
 *
 * <p>Select this provider at runtime with {@code FINANCEAGENT_CREDENTIALS=aws}.
 */
public final class AwsSecretsManagerCredentialsProvider implements CredentialsProvider {

    private static final String SCOPE_SHARED = "shared";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final String firmId;
    private final Function<String, String> credentialScopeFor;
    private final SecretsManagerClient secretsManager;

    public AwsSecretsManagerCredentialsProvider(String firmId,
                                                Function<String, String> credentialScopeFor) {
        this(firmId, credentialScopeFor, SecretsManagerClient.create());
    }

    AwsSecretsManagerCredentialsProvider(String firmId,
                                         Function<String, String> credentialScopeFor,
                                         SecretsManagerClient secretsManager) {
        this.firmId = firmId;
        this.credentialScopeFor = credentialScopeFor;
        this.secretsManager = secretsManager;
    }

    @Override
    public PortalCredentials get(String portalId) {
        String scope = credentialScopeFor.apply(portalId);
        String secretId = SCOPE_SHARED.equals(scope)
                ? "financeagent/shared/portals/" + portalId
                : "financeagent/firms/" + firmId + "/portals/" + portalId;

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
}
