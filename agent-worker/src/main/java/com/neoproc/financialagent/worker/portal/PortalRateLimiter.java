package com.neoproc.financialagent.worker.portal;

import com.google.common.util.concurrent.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Per-portal concurrency and rate enforcement. Static registries are keyed by
 * {@code portalId} and live for the JVM lifetime — safe for both the CLI runner
 * and the queue consumer (P1 Item 6), where multiple threads may target the
 * same portal concurrently.
 *
 * <p>Two independent controls:
 * <ul>
 *   <li><b>Semaphore</b> — caps parallel agent runs ({@code maxConcurrent}).
 *       Shared-credential portals (e.g. AutoPlanilla) set this to 1 to prevent
 *       concurrent sessions from fighting over the same login.</li>
 *   <li><b>RateLimiter</b> — enforces a minimum wall-clock gap between
 *       consecutive runs ({@code minIntervalSeconds}).  Prevents bursting
 *       even when maxConcurrent > 1.</li>
 * </ul>
 *
 * <p>Usage (try-with-resources ensures the semaphore is always released):
 * <pre>{@code
 *   try (PortalRateLimiter.Permit p = PortalRateLimiter.acquire(descriptor)) {
 *       engine.runSteps(...);
 *   }
 * }</pre>
 *
 * <p>If {@link PortalDescriptor#rateLimit()} is {@code null}, {@code acquire}
 * returns a no-op permit immediately — backward compatible with descriptors that
 * predate this field.
 */
public final class PortalRateLimiter {

    private static final ConcurrentHashMap<String, Semaphore> SEMAPHORES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, RateLimiter> RATE_LIMITERS = new ConcurrentHashMap<>();

    private PortalRateLimiter() {}

    /**
     * Acquires portal access permits for the given descriptor.
     * Blocks until both the concurrency semaphore and the rate limiter allow.
     *
     * @return a {@link Permit} that MUST be closed (use try-with-resources)
     * @throws InterruptedException if the thread is interrupted while waiting for the semaphore
     */
    public static Permit acquire(PortalDescriptor descriptor) throws InterruptedException {
        PortalDescriptor.RateLimit config = descriptor.rateLimit();
        if (config == null) {
            return () -> {};
        }

        String portalId = descriptor.id();

        Semaphore semaphore = SEMAPHORES.computeIfAbsent(portalId,
                id -> new Semaphore(config.maxConcurrentOrDefault(), true));
        semaphore.acquire();

        double minInterval = config.minIntervalSecondsOrDefault();
        if (minInterval > 0.0) {
            RATE_LIMITERS.computeIfAbsent(portalId,
                    id -> RateLimiter.create(1.0 / minInterval)).acquire();
        }

        return semaphore::release;
    }

    /**
     * A handle returned by {@link #acquire}. Closing it releases the
     * concurrency semaphore so the next waiting run can proceed.
     */
    @FunctionalInterface
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
