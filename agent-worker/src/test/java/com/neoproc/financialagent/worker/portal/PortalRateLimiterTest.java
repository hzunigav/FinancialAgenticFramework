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

    @Test
    void sharedCredentialPortalSerialisesEveryone() {
        // INS RT-Virtual has one login for the whole fleet, so every run
        // contends regardless of which client it is for.
        PortalDescriptor ins = descriptorWithScope("ins-rt-virtual", "shared", "ins-rt-virtual");

        assertEquals(PortalRateLimiter.sessionKey(ins, "1", "3101680139"),
                PortalRateLimiter.sessionKey(ins, "2", "3999999999"),
                "shared credentials mean one session for everyone");
    }

    @Test
    void perClientPortalKeepsCompaniesIndependent() {
        // CCSS Sicere logs in per company. One company's payroll must not delay
        // another company's reports.
        PortalDescriptor submit = descriptorWithScope("ccss-sicere", "per-client", "ccss-sicere");
        PortalDescriptor reports = descriptorWithScope("ccss-sicere-reports", "per-client", "ccss-sicere");

        assertNotEquals(PortalRateLimiter.sessionKey(submit, "1", "3101680139"),
                PortalRateLimiter.sessionKey(submit, "1", "3999999999"),
                "different companies hold different CCSS sessions");

        assertEquals(PortalRateLimiter.sessionKey(submit, "1", "3101680139"),
                PortalRateLimiter.sessionKey(reports, "1", "3101680139"),
                "submit and reports share one company's session");
    }

    @Test
    void perClientKeyIgnoresFirmBecauseThePortalBindsToTheCompany() {
        // Two tenants holding credentials for the same company still contend
        // for that company's single portal session.
        PortalDescriptor ccss = descriptorWithScope("ccss-sicere", "per-client", "ccss-sicere");

        assertEquals(PortalRateLimiter.sessionKey(ccss, "1", "3101680139"),
                PortalRateLimiter.sessionKey(ccss, "2", "3101680139"));
    }

    @Test
    void missingClientIdentifierCollapsesToOneBucket() {
        // Over-serialising costs latency; under-serialising drops a live
        // session mid-run. Absent identity takes the safe side.
        PortalDescriptor ccss = descriptorWithScope("ccss-sicere", "per-client", "ccss-sicere");

        assertEquals(PortalRateLimiter.sessionKey(ccss, "1", null),
                PortalRateLimiter.sessionKey(ccss, "1", "  "));
    }

    @Test
    void perFirmPortalKeysOnTheFirm() {
        PortalDescriptor mock = descriptorWithScope("mock-payroll", "per-firm", null);

        assertNotEquals(PortalRateLimiter.sessionKey(mock, "1", null),
                PortalRateLimiter.sessionKey(mock, "2", null));
    }

    @Test
    void descriptorsSharingAGroupContendForOnePermit() throws Exception {
        // CCSS Sicere and INS RT-Virtual allow only ONE active session per
        // account. Submit, capture and report pulls are separate descriptors
        // sharing one login, so without a shared group each would get its own
        // "max 1" permit and a report run could start mid-payroll.
        String group = uniqueId();
        PortalDescriptor submit = minimalDescriptor(uniqueId(),
                new PortalDescriptor.RateLimit(1, null, group));
        PortalDescriptor reports = minimalDescriptor(uniqueId(),
                new PortalDescriptor.RateLimit(1, null, group));

        PortalRateLimiter.Permit heldBySubmit = PortalRateLimiter.acquire(submit);

        AtomicBoolean reportsStarted = new AtomicBoolean(false);
        CountDownLatch launched = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        Future<?> reportRun = executor.submit(() -> {
            launched.countDown();
            try (PortalRateLimiter.Permit p = PortalRateLimiter.acquire(reports)) {
                reportsStarted.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(launched.await(2, TimeUnit.SECONDS));
        Thread.sleep(250);
        assertFalse(reportsStarted.get(),
                "report run must wait while the submit run holds the portal session");

        heldBySubmit.close();
        reportRun.get(5, TimeUnit.SECONDS);
        assertTrue(reportsStarted.get(), "report run should proceed once the submit releases");
        executor.shutdownNow();
    }

    @Test
    void descriptorsWithoutAGroupDoNotContend() throws Exception {
        // The default remains one bucket per descriptor id, so unrelated
        // portals never block each other.
        PortalDescriptor a = minimalDescriptor(uniqueId(), new PortalDescriptor.RateLimit(1, null));
        PortalDescriptor b = minimalDescriptor(uniqueId(), new PortalDescriptor.RateLimit(1, null));

        try (PortalRateLimiter.Permit p1 = PortalRateLimiter.acquire(a);
             PortalRateLimiter.Permit p2 = PortalRateLimiter.acquire(b)) {
            assertNotNull(p1);
            assertNotNull(p2);
        }
    }

    /** Descriptor carrying the credential scope and session group under test. */
    private static PortalDescriptor descriptorWithScope(String id, String scope, String group) {
        return new PortalDescriptor(id, null, "http://localhost", false,
                scope, new PortalDescriptor.RateLimit(1, null, group),
                null, null, null, null, null, null);
    }

    private static PortalDescriptor minimalDescriptor(String id, PortalDescriptor.RateLimit rateLimit) {
        return new PortalDescriptor(id, null, "http://localhost", false,
                "per-firm", rateLimit, null, null, null, null, null, null);
    }
}
