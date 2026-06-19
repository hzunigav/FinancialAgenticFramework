package com.neoproc.financialagent.common.session;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AwsSecretsManagerSessionStore} via an in-memory
 * {@link AwsSecretsManagerSessionStore.SecretOps} fake — exercises the
 * round-trip, the age-based TTL (stale → purged), explicit purge, and the
 * absent case without touching AWS (and without mocking the final
 * {@code SecretsManagerClient}, which Mockito can't rewrite on Java 25).
 */
class AwsSecretsManagerSessionStoreTest {

    private static final String PREFIX = "test/financeagent/sessions/";
    private static final String PORTAL = "xero";
    private static final String SECRET_ID = PREFIX + PORTAL;
    private static final String SESSION = "{\"cookies\":[{\"name\":\"_abck\"}],\"origins\":[]}";

    @Test
    void saveThenLoadRoundTrips() {
        FakeSecretOps ops = new FakeSecretOps();
        AwsSecretsManagerSessionStore store = new AwsSecretsManagerSessionStore(ops, PREFIX);

        store.save(PORTAL, SESSION);

        Optional<String> loaded = store.load(PORTAL, Duration.ofMinutes(60));
        assertTrue(loaded.isPresent());
        assertEquals(SESSION, loaded.get());
        assertTrue(ops.store.get(SECRET_ID).contains("savedAt"), "stored value must be the {savedAt,session} wrapper");
    }

    @Test
    void absentSessionReturnsEmpty() {
        AwsSecretsManagerSessionStore store = new AwsSecretsManagerSessionStore(new FakeSecretOps(), PREFIX);
        assertTrue(store.load(PORTAL, Duration.ofMinutes(60)).isEmpty());
    }

    @Test
    void staleSessionIsPurgedAndReturnsEmpty() {
        FakeSecretOps ops = new FakeSecretOps();
        AwsSecretsManagerSessionStore store = new AwsSecretsManagerSessionStore(ops, PREFIX);
        store.save(PORTAL, SESSION);

        // maxAge of zero: anything already saved is older than "now - 0".
        Optional<String> loaded = store.load(PORTAL, Duration.ZERO);

        assertTrue(loaded.isEmpty(), "a session older than maxAge must not be returned");
        assertFalse(ops.store.containsKey(SECRET_ID), "stale session must be purged");
    }

    @Test
    void purgeRemovesTheSecret() {
        FakeSecretOps ops = new FakeSecretOps();
        AwsSecretsManagerSessionStore store = new AwsSecretsManagerSessionStore(ops, PREFIX);
        store.save(PORTAL, SESSION);

        store.purge(PORTAL);

        assertFalse(ops.store.containsKey(SECRET_ID));
        assertTrue(store.load(PORTAL, Duration.ofMinutes(60)).isEmpty());
    }

    @Test
    void corruptValueIsPurgedAndReturnsEmpty() {
        FakeSecretOps ops = new FakeSecretOps();
        ops.store.put(SECRET_ID, "not-json");
        AwsSecretsManagerSessionStore store = new AwsSecretsManagerSessionStore(ops, PREFIX);

        assertTrue(store.load(PORTAL, Duration.ofMinutes(60)).isEmpty());
        assertFalse(ops.store.containsKey(SECRET_ID), "unparseable session must be purged");
    }

    /** In-memory {@link AwsSecretsManagerSessionStore.SecretOps} — upsert semantics. */
    private static final class FakeSecretOps implements AwsSecretsManagerSessionStore.SecretOps {
        final Map<String, String> store = new HashMap<>();

        @Override
        public Optional<String> get(String secretId) {
            return Optional.ofNullable(store.get(secretId));
        }

        @Override
        public void put(String secretId, String value) {
            store.put(secretId, value);
        }

        @Override
        public void delete(String secretId) {
            store.remove(secretId);
        }
    }
}
