package com.neoproc.financialagent.worker.idempotency;

import java.util.Optional;

/**
 * Caches the result envelope produced for each {@code envelopeId} so a
 * redelivery (SQS visibility-timeout expiry, listener retry, worker
 * crash-and-restart of an in-flight message) can re-publish the original
 * outcome instead of re-executing the portal.
 *
 * <p>Two-stage API:
 * <ol>
 *   <li>{@link #lookup(String)} — called on every receive. A hit returns
 *       the cached result envelope; the listener re-publishes it without
 *       touching the portal.</li>
 *   <li>{@link #cacheResult(String, Object)} — called once after the
 *       portal interaction completes (success or business failure). The
 *       cached entry serves all subsequent redeliveries of the same
 *       envelopeId.</li>
 * </ol>
 *
 * <p>Phase 1 implementation is in-memory ({@link InMemoryIdempotencyStore}).
 * Swap for a Redis or Postgres-backed implementation at M6 with no changes
 * to callers.
 */
public interface IdempotencyStore {

    /**
     * @return the cached result envelope previously stored for
     *         {@code envelopeId}, or empty if this id has not been seen
     *         (or was seen but evicted under TTL/size pressure or lost
     *         in a worker restart — i.e. cold-cache).
     */
    Optional<Object> lookup(String envelopeId);

    /**
     * Records the result envelope produced for {@code envelopeId}. Idempotent —
     * a subsequent call with the same id is a no-op (first-write-wins
     * preserves the original cached result against parallel/retry races).
     */
    void cacheResult(String envelopeId, Object result);
}
