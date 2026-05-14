package com.neoproc.financialagent.worker.idempotency;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, TTL-keyed in-memory implementation of {@link IdempotencyStore}.
 *
 * <p>Stores the full result envelope per envelopeId. On a duplicate
 * delivery the listener re-publishes the cached envelope without touching
 * the portal — required by SqsMigrationPlan.md §6.3 for the
 * throw-on-publish-failure recovery contract to be safe (without it, a
 * redelivery after a transient publish failure would emit a synthetic
 * DUPLICATE_ENVELOPE failure for an envelope whose portal work succeeded).
 *
 * <p>Sized at 10 000 entries × ~10 KB envelope ≈ 100 MB worst case. At
 * ~10 envelopes per cycle and weekly cadence the bound is years away from
 * binding; eviction in normal operation comes from the 7-day TTL.
 *
 * <p>Does not survive worker restarts. A restart between original receive
 * and redelivery is the realistic cold-cache scenario; the listener
 * detects it via SQS {@code ApproximateReceiveCount > 1} with no cached
 * entry and emits {@code EXPIRED_DUPLICATE} (per the cold-cache pin in
 * SqsMigrationPlan.md §6.6 Q6b answer). Persistent backing store
 * (Redis/DynamoDB) tracked in EnhancementsBacklog.md.
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Cache<String, Object> cache = Caffeine.newBuilder()
            .expireAfterWrite(7, TimeUnit.DAYS)
            .maximumSize(10_000)
            .build();

    @Override
    public Optional<Object> lookup(String envelopeId) {
        return Optional.ofNullable(cache.getIfPresent(envelopeId));
    }

    @Override
    public void cacheResult(String envelopeId, Object result) {
        // First-write-wins: a parallel retry that lands here second must not
        // overwrite the result the first attempt already published.
        cache.asMap().putIfAbsent(envelopeId, result);
    }
}
