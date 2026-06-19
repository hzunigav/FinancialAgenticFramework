package com.neoproc.financialagent.common.session;

/**
 * Selects the {@link SessionStore} implementation the same way
 * {@link com.neoproc.financialagent.common.credentials.CredentialsProvider}
 * selection works: {@code FINANCEAGENT_CREDENTIALS=aws} → the persistent
 * AWS Secrets Manager + KMS store (survives Fargate scale-to-zero); anything
 * else → the encrypted local-file store for dev/CLI.
 */
public final class SessionStores {

    private SessionStores() {}

    public static SessionStore defaultStore() {
        if ("aws".equalsIgnoreCase(System.getenv("FINANCEAGENT_CREDENTIALS"))) {
            return new AwsSecretsManagerSessionStore();
        }
        return new LocalEncryptedSessionStore();
    }
}
