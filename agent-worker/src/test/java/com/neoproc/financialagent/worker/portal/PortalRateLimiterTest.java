package com.neoproc.financialagent.worker.portal;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PortalRateLimiterTest {

    // Each test uses a unique portal ID so static registry entries don't interfere.

    @Test
    void nullRateLimit_acquiresImmediately() throws InterruptedException {
        PortalDescriptor descriptor = minimalDescriptor(uniqueId(), null);
        try (PortalRateLimiter.Permit permit = PortalRateLimiter.acquire(descriptor)) {
            assertNotNull(permit);
        }
    }

    @Test
    void maxConcurrent1_secondAcquireBlocksUntilFirstReleased() throws Exception {
        String id = uniqueId();
        PortalDescriptor descriptor = minimalDescriptor(id,
                new PortalDescriptor.RateLimit(1, null));

        PortalRateLimiter.Permit first = PortalRateLimiter.acquire(descriptor);

        AtomicBoolean secondAcquired = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);

        var executor = Executors.newSingleThreadExecutor();
        Future<?> secondThread = executor.submit(() -> {
            try {
                started.countDown();
                PortalRateLimiter.Permit second = PortalRateLimiter.acquire(descriptor);
                secondAcquired.set(true);
                second.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        started.await();
        // Give the second thread time to block on the semaphore
        TimeUnit.MILLISECONDS.sleep(100);
        assertFalse(secondAcquired.get(), "second acquire should be blocked while first permit is held");

        first.close(); // release — unblocks the second thread
        secondThread.get(2, TimeUnit.SECONDS);
        assertTrue(secondAcquired.get(), "second acquire should succeed after first permit is closed");
        executor.shutdown();
    }

    @Test
    void permit_isReusable_afterClose() throws InterruptedException {
        String id = uniqueId();
        PortalDescriptor descriptor = minimalDescriptor(id,
                new PortalDescriptor.RateLimit(1, null));

        for (int i = 0; i < 3; i++) {
            try (PortalRateLimiter.Permit permit = PortalRateLimiter.acquire(descriptor)) {
                assertNotNull(permit);
            }
        }
    }

    @Test
    void minIntervalSeconds_zero_doesNotAddRateLimiter() throws InterruptedException {
        // minIntervalSeconds=0 should be treated as no interval limit — both
        // acquire calls complete without measurable delay.
        String id = uniqueId();
        PortalDescriptor descriptor = minimalDescriptor(id,
                new PortalDescriptor.RateLimit(2, 0.0));

        long start = System.currentTimeMillis();
        try (PortalRateLimiter.Permit p1 = PortalRateLimiter.acquire(descriptor);
             PortalRateLimiter.Permit p2 = PortalRateLimiter.acquire(descriptor)) {
            // both acquired without delay
        }
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 500, "zero minInterval should not introduce delay, elapsed=" + elapsed + "ms");
    }

    @Test
    void rateLimitDefaults_maxConcurrent1_minInterval0() {
        PortalDescriptor.RateLimit config = new PortalDescriptor.RateLimit(null, null);
        assertEquals(1, config.maxConcurrentOrDefault());
        assertEquals(0.0, config.minIntervalSecondsOrDefault());
    }

    // --- helpers ---

    private static String uniqueId() {
        return "test-" + UUID.randomUUID();
    }

    private static PortalDescriptor minimalDescriptor(String id, PortalDescriptor.RateLimit rateLimit) {
        return new PortalDescriptor(id, null, "http://localhost", false,
                "per-firm", rateLimit, null, null, null, null, null);
    }
}
