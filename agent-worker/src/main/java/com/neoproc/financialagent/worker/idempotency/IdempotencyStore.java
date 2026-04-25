package com.neoproc.financialagent.worker.idempotency;

/**
 * Tracks processed envelope IDs so the worker never re-executes a portal
 * action for a message it already handled (portals are not idempotent).
 *
 * <p>Phase 1 uses {@link InMemoryIdempotencyStore}. Swap for a Redis or
 * Postgres-backed implementation at M6 with no changes to callers.
 */
public interface IdempotencyStore {

    /**
     * Records {@code envelopeId} as processed.
     *
     * @return {@code true} if this is the first time the id has been seen
     *         (the caller should process it); {@code false} if it is a
     *         duplicate (the caller should skip portal execution).
     */
    boolean tryRecord(String envelopeId);
}
