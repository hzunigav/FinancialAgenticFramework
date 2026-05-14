package com.neoproc.financialagent.worker.idempotency;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryIdempotencyStoreTest {

    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();

    @Test
    void unseenEnvelopeIdReturnsEmpty() {
        assertTrue(store.lookup("never-seen").isEmpty());
    }

    @Test
    void cachedResultIsReturnedOnLookup() {
        Object payload = new Object();
        store.cacheResult("env-1", payload);

        Optional<Object> cached = store.lookup("env-1");
        assertTrue(cached.isPresent());
        assertSame(payload, cached.get());
    }

    @Test
    void firstWriteWinsAgainstParallelRetry() {
        Object original = "first";
        Object racingDuplicate = "second";

        store.cacheResult("env-2", original);
        store.cacheResult("env-2", racingDuplicate);  // simulates a parallel retry

        // First write must survive — round-trip safety: a second attempt
        // landing here after the original published must NOT overwrite the
        // canonical result.
        assertEquals("first", store.lookup("env-2").orElseThrow());
    }

    @Test
    void independentEnvelopeIdsDoNotCollide() {
        store.cacheResult("env-a", "result-a");
        store.cacheResult("env-b", "result-b");

        assertEquals("result-a", store.lookup("env-a").orElseThrow());
        assertEquals("result-b", store.lookup("env-b").orElseThrow());
        assertFalse(store.lookup("env-c").isPresent());
    }
}
