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
    /**
     * @deprecated only correct for shared-credential portals. Pass the tenant
     *     identity so per-firm and per-client portals are serialised per login
     *     instead of all together.
     */
    @Deprecated
    public static Permit acquire(PortalDescriptor descriptor) throws InterruptedException {
        return acquire(descriptor, null, null);
    }

    public static Permit acquire(PortalDescriptor descriptor,
                                 String firmId,
                                 String clientIdentifier) throws InterruptedException {
        PortalDescriptor.RateLimit config = descriptor.rateLimit();
        if (config == null) {
            return () -> {};
        }

        // Two controls, two different keys — they protect different things.
        //
        // The semaphore protects a LOGIN: CCSS Sicere and INS RT-Virtual permit
        // only one active session per set of credentials, so it keys on the
        // credential identity. INS is shared-credential, so every INS run in
        // the fleet serialises; CCSS is per-client, so one company's payroll
        // must not delay another company's reports.
        //
        // The rate limiter protects a HOST: the minimum gap between requests is
        // politeness toward the portal's servers, which do not care whose
        // credentials are in play. Driving twenty companies at once would be
        // twenty times the load, so it keys on the group.
        String sessionKey = sessionKey(descriptor, firmId, clientIdentifier);
        String hostKey = config.groupOrDefault(descriptor.id());

        Semaphore semaphore = SEMAPHORES.computeIfAbsent(sessionKey,
                id -> new Semaphore(config.maxConcurrentOrDefault(), true));
        semaphore.acquire();

        double minInterval = config.minIntervalSecondsOrDefault();
        if (minInterval > 0.0) {
            RATE_LIMITERS.computeIfAbsent(hostKey,
                    id -> RateLimiter.create(1.0 / minInterval)).acquire();
        }

        return semaphore::release;
    }

    /**
     * The identity of the login a run will occupy.
     *
     * <p>Derived from {@code credentialScope}, which already models exactly
     * this: a shared-credential portal has one login for everyone, a per-client
     * portal has one per company. Per-client keys on the client alone and not
     * on the firm, because the portal binds the session to the corporate id —
     * two tenants holding credentials for the same company still contend for
     * that company's single session.
     *
     * <p>A missing identifier collapses to one shared bucket rather than
     * spreading across unique keys. Over-serialising costs latency; under-
     * serialising gets a live session dropped mid-run.
     */
    static String sessionKey(PortalDescriptor descriptor, String firmId, String clientIdentifier) {
        String group = descriptor.rateLimit() == null
                ? descriptor.id()
                : descriptor.rateLimit().groupOrDefault(descriptor.id());
        if (descriptor.hasSharedCredentials()) {
            return group;
        }
        if (descriptor.hasPerClientCredentials()) {
            return group + "::client:" + orUnknown(clientIdentifier);
        }
        return group + "::firm:" + orUnknown(firmId);
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
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
