package com.neoproc.financialagent.worker.idempotency;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Bounded, TTL-keyed in-memory implementation of {@link IdempotencyStore}.
 *
 * <p>Entries expire after 7 days (matching the handoff §C.4 requirement).
 * Bounded at 50 000 entries — well above any realistic burst — to prevent
 * unbounded heap growth on a long-lived worker. Entries evicted by the
 * size bound before the TTL elapses are a correctness risk (late duplicates
 * could slip through), but the bound would only be hit under sustained
 * high-volume replay, which is far outside normal operation.
 *
 * <p>Does not survive worker restarts. A restart that triggers redelivery
 * of an already-processed message will result in a duplicate portal action.
 * Acceptable for Phase 1 where Praxis manages retries via new envelopeIds;
 * swap for a durable store (Redis/Postgres) at M6.
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Cache<String, Boolean> seen = Caffeine.newBuilder()
            .expireAfterWrite(7, TimeUnit.DAYS)
            .maximumSize(50_000)
            .build();

    @Override
    public boolean tryRecord(String envelopeId) {
        return seen.asMap().putIfAbsent(envelopeId, Boolean.TRUE) == null;
    }
}
