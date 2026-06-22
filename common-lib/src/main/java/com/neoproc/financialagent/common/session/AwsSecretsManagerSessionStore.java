package com.neoproc.financialagent.common.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DeleteSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Production {@link SessionStore} backed by AWS Secrets Manager (encrypted
 * at rest with KMS). Persists a portal's Playwright {@code storageState} so an
 * authenticated session survives Fargate scale-to-zero / container restarts —
 * the worker reuses it and only re-authenticates when it goes stale.
 *
 * <p>One secret per portal: {@code [envPrefix/]financeagent/sessions/<portalId>}
 * (mirrors {@link com.neoproc.financialagent.common.credentials.AwsSecretsManagerCredentialsProvider}'s
 * {@code FINANCEAGENT_SECRETS_ENV_PREFIX} convention). The stored value is a
 * small JSON wrapper {@code {savedAt, session}} so age-based TTL works the same
 * as the local store; Secrets Manager + KMS provide the encryption (optionally
 * a customer-managed key via {@code FINANCEAGENT_SESSION_KMS_KEY_ID}).
 *
 * <p>Select at runtime with {@code FINANCEAGENT_CREDENTIALS=aws} (see
 * {@link SessionStores}). The worker needs {@code secretsmanager:GetSecretValue
 * /PutSecretValue/CreateSecret/DeleteSecret} on the session-secret path and
 * {@code kms:Encrypt/Decrypt/GenerateDataKey} on the key.
 *
 * <p>All Secrets Manager calls go through {@link SecretOps} so the store's
 * TTL/serialization logic is unit-testable with an in-memory fake (no mocking
 * of the final {@link SecretsManagerClient}).
 */
public final class AwsSecretsManagerSessionStore implements SessionStore {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final SecretOps ops;
    private final String prefix;

    public AwsSecretsManagerSessionStore() {
        this(new SecretsManagerSecretOps(
                        SecretsManagerClient.create(),
                        System.getenv("FINANCEAGENT_SESSION_KMS_KEY_ID")),
                normalizePrefix(System.getenv("FINANCEAGENT_SECRETS_ENV_PREFIX")) + "financeagent/sessions/");
    }

    AwsSecretsManagerSessionStore(SecretOps ops, String prefix) {
        this.ops = ops;
        this.prefix = prefix;
    }

    @Override
    public Optional<String> load(String portalId, Duration maxAge) {
        String secretId = prefix + portalId;
        Optional<String> raw = ops.get(secretId);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            Stored stored = JSON.readValue(raw.get(), Stored.class);
            if (Duration.between(stored.savedAt(), Instant.now()).compareTo(maxAge) > 0) {
                ops.delete(secretId);     // stale — force re-auth
                return Optional.empty();
            }
            return Optional.ofNullable(stored.session());
        } catch (Exception corruptOrUnreadable) {
            ops.delete(secretId);         // unparseable — purge and re-auth
            return Optional.empty();
        }
    }

    @Override
    public void save(String portalId, String sessionJson) {
        try {
            String value = JSON.writeValueAsString(new Stored(Instant.now(), sessionJson));
            ops.put(prefix + portalId, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save session for " + portalId, e);
        }
    }

    @Override
    public void purge(String portalId) {
        ops.delete(prefix + portalId);
    }

    private static String normalizePrefix(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return raw.endsWith("/") ? raw : raw + "/";
    }

    /** Stored wrapper — {@code savedAt} drives TTL, {@code session} is the storageState JSON. */
    record Stored(Instant savedAt, String session) {}

    /**
     * Minimal seam over the three Secrets Manager operations the store needs.
     * The AWS implementation is {@link SecretsManagerSecretOps}; tests supply
     * an in-memory fake.
     */
    interface SecretOps {
        Optional<String> get(String secretId);

        /** Upsert — creates the secret if it does not exist yet. */
        void put(String secretId, String value);

        /** Removes the secret; no-op if absent. */
        void delete(String secretId);
    }

    /** AWS-backed {@link SecretOps}. */
    static final class SecretsManagerSecretOps implements SecretOps {

        private final SecretsManagerClient client;
        private final String kmsKeyId; // nullable — null = Secrets Manager default key

        SecretsManagerSecretOps(SecretsManagerClient client, String kmsKeyId) {
            this.client = client;
            this.kmsKeyId = (kmsKeyId == null || kmsKeyId.isBlank()) ? null : kmsKeyId;
        }

        @Override
        public Optional<String> get(String secretId) {
            try {
                String value = client.getSecretValue(
                        GetSecretValueRequest.builder().secretId(secretId).build()).secretString();
                return Optional.ofNullable(value);
            } catch (ResourceNotFoundException e) {
                return Optional.empty();
            }
        }

        @Override
        public void put(String secretId, String value) {
            try {
                client.putSecretValue(PutSecretValueRequest.builder()
                        .secretId(secretId).secretString(value).build());
            } catch (ResourceNotFoundException firstWrite) {
                CreateSecretRequest.Builder create = CreateSecretRequest.builder()
                        .name(secretId).secretString(value);
                if (kmsKeyId != null) {
                    create.kmsKeyId(kmsKeyId);
                }
                client.createSecret(create.build());
            }
        }

        @Override
        public void delete(String secretId) {
            try {
                client.deleteSecret(DeleteSecretRequest.builder()
                        .secretId(secretId)
                        .forceDeleteWithoutRecovery(true)   // sessions are not precious
                        .build());
            } catch (ResourceNotFoundException ignored) {
                // already gone
            }
        }
    }
}
