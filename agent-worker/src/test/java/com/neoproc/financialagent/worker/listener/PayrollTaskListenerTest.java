package com.neoproc.financialagent.worker.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.payroll.EnvelopeStatus;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitRequest;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitResult;
import com.neoproc.financialagent.contract.payroll.SubmitRequestBody;
import com.neoproc.financialagent.contract.payroll.SubmitResultBody;
import com.neoproc.financialagent.contract.payroll.SubmitTask;
import com.neoproc.financialagent.worker.PortalRunService;
import com.neoproc.financialagent.worker.RunOutcome;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import com.neoproc.financialagent.worker.idempotency.InMemoryIdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessageHeaders;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Listener-level tests for the SQS submit flow. Exercises the four
 * recovery paths defined in SqsMigrationPlan.md §6.3:
 *
 * <ol>
 *   <li>Fresh delivery → portal runs, result cached, result published.</li>
 *   <li>Warm-cache duplicate → cached result re-published, portal NOT run.</li>
 *   <li>Cold-cache duplicate (receiveCount > 1, no cached entry) → synthetic
 *       {@code EXPIRED_DUPLICATE} emitted, portal NOT run.</li>
 *   <li>Terminal publish failure → listener throws, allowing SQS redelivery.</li>
 * </ol>
 *
 * <p>Uses the real {@link InMemoryIdempotencyStore} so cache-state
 * transitions are observable; mocks {@link PortalRunService} and
 * {@link SqsRetryablePublisher}. Default {@code FINANCEAGENT_CIPHER}
 * (cleartext) is sufficient — no key infrastructure needed.
 */
class PayrollTaskListenerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String BUSINESS_KEY = "test-bk";
    private static final long FIRM_ID = 1L;
    private static final String PORTAL_ID = "mock-payroll";
    private static final String RESULTS_QUEUE = "test-financeagent-results";

    private PortalRunService portalRunService;
    private SqsRetryablePublisher publisher;
    private InMemoryIdempotencyStore idempotencyStore;
    private PayrollTaskListener listener;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        portalRunService = mock(PortalRunService.class);
        publisher = mock(SqsRetryablePublisher.class);
        idempotencyStore = new InMemoryIdempotencyStore();

        listener = new PayrollTaskListener(portalRunService, idempotencyStore, publisher);
        ReflectionTestUtils.setField(listener, "portalId", PORTAL_ID);
        ReflectionTestUtils.setField(listener, "resultsQueue", RESULTS_QUEUE);

        // Default mock: portal run produces a SUCCESS result file in tempDir.
        // Individual tests can override or verify(never()) as needed.
        writeMockResultFile("SUCCESS");
        lenient().when(portalRunService.run(eq(PORTAL_ID), any(PayrollSubmitRequest.class), anyMap()))
                .thenReturn(new RunOutcome(tempDir, "SUCCESS"));
    }

    // -----------------------------------------------------------------------
    // Path 1: fresh delivery
    // -----------------------------------------------------------------------

    @Test
    void freshDeliveryRunsPortalAndPublishesResult() throws Exception {
        String envelopeId = "env-fresh";
        listener.onSubmitRequest(buildRequestJson(envelopeId), envelopeId, headersWithReceiveCount(1));

        verify(portalRunService, times(1)).run(eq(PORTAL_ID), any(), anyMap());
        PayrollSubmitResult published = capturePublished();
        assertEquals(BUSINESS_KEY, published.envelope().businessKey());

        // Result is cached for redelivery recovery.
        Optional<Object> cached = idempotencyStore.lookup(envelopeId);
        assertTrue(cached.isPresent());
        assertSame(published, cached.get());
    }

    // -----------------------------------------------------------------------
    // Path 2: warm-cache duplicate
    // -----------------------------------------------------------------------

    @Test
    void warmCacheDuplicateRePublishesCachedResultWithoutPortalReExecution() throws Exception {
        String envelopeId = "env-warm";

        // First delivery — populates the cache.
        listener.onSubmitRequest(buildRequestJson(envelopeId), envelopeId, headersWithReceiveCount(1));
        PayrollSubmitResult original = capturePublished();

        // Second delivery (same envelopeId) — cache hit.
        listener.onSubmitRequest(buildRequestJson(envelopeId), envelopeId, headersWithReceiveCount(2));

        // Portal must NOT be invoked a second time.
        verify(portalRunService, times(1)).run(eq(PORTAL_ID), any(), anyMap());

        // Publisher invoked twice; second call carries the same cached envelope.
        ArgumentCaptor<PayrollSubmitResult> captor = ArgumentCaptor.forClass(PayrollSubmitResult.class);
        verify(publisher, times(2)).publish(eq(RESULTS_QUEUE), captor.capture(),
                eq(PayrollSubmitResult.SCHEMA), anyString(), eq(BUSINESS_KEY));
        assertSame(original, captor.getAllValues().get(1),
                "warm-cache duplicate must re-publish the IDENTICAL cached envelope, not a new one");
    }

    // -----------------------------------------------------------------------
    // Path 3: cold-cache duplicate (the named test from SqsMigrationPlan.md §6.6 Q6b answer)
    // -----------------------------------------------------------------------

    @Test
    void coldCacheDuplicateEmitsExpiredDuplicateNotReExecution() throws Exception {
        // Setup: receiveCount > 1 simulates SQS redelivery; idempotency store
        // is empty (cold-cache — original processing was lost in a worker
        // restart, or original is still in-flight on another container).
        String envelopeId = "env-cold";

        listener.onSubmitRequest(buildRequestJson(envelopeId), envelopeId, headersWithReceiveCount(2));

        // CRITICAL: portal must NOT be invoked. Re-running can create
        // duplicate filings on non-idempotent portals (Hacienda OVI).
        verify(portalRunService, never()).run(any(), any(), any());

        // A FAILED result with category=EXPIRED_DUPLICATE is published.
        PayrollSubmitResult published = capturePublished();
        SubmitResultBody body = (SubmitResultBody) published.result();
        assertEquals(EnvelopeStatus.FAILED, body.status());
        assertNotNull(body.errorDetail(), "EXPIRED_DUPLICATE result must carry an errorDetail block");
        assertEquals("EXPIRED_DUPLICATE", body.errorDetail().category());
        assertEquals(BUSINESS_KEY, published.envelope().businessKey(),
                "businessKey passthrough — cold-cache result must echo request's businessKey");

        // The synthetic EXPIRED_DUPLICATE is itself cached, so any further
        // redeliveries get the same response (consistent with warm-cache path).
        assertTrue(idempotencyStore.lookup(envelopeId).isPresent());
    }

    // -----------------------------------------------------------------------
    // Path 4: publish failure → throw for SQS redelivery
    // -----------------------------------------------------------------------

    @Test
    void terminalPublishFailureThrowsForSqsRedelivery() throws Exception {
        // Publisher throws PublishFailedException after exhausting its retries.
        doThrow(new SqsRetryablePublisher.PublishFailedException("retries exhausted",
                new RuntimeException("SQS down")))
                .when(publisher).publish(anyString(), any(), anyString(), anyString(), anyString());

        String envelopeId = "env-pub-fail";

        // Listener must let the exception propagate — Spring Cloud AWS
        // skips DeleteMessage, SQS redelivers after visibility timeout.
        assertThrows(SqsRetryablePublisher.PublishFailedException.class,
                () -> listener.onSubmitRequest(
                        buildRequestJson(envelopeId), envelopeId, headersWithReceiveCount(1)));
    }

    // -----------------------------------------------------------------------
    // Receive-count extraction helper test (header-name compatibility)
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"Sqs_ApproximateReceiveCount", "ApproximateReceiveCount"})
    void receiveCountExtractedFromKnownHeaderNames(String headerName) {
        Map<String, Object> raw = new HashMap<>();
        raw.put(headerName, "3");
        assertEquals(3, PayrollTaskListener.extractReceiveCount(new MessageHeaders(raw)));
    }

    @Test
    void missingReceiveCountHeaderDefaultsToOne() {
        assertEquals(1, PayrollTaskListener.extractReceiveCount(new MessageHeaders(Map.of())));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private PayrollSubmitResult capturePublished() {
        ArgumentCaptor<PayrollSubmitResult> captor = ArgumentCaptor.forClass(PayrollSubmitResult.class);
        verify(publisher).publish(eq(RESULTS_QUEUE), captor.capture(),
                eq(PayrollSubmitResult.SCHEMA), anyString(), eq(BUSINESS_KEY));
        return captor.getValue();
    }

    private void writeMockResultFile(String status) throws Exception {
        SubmitResultBody body = new SubmitResultBody(
                status, null,
                new SubmitResultBody.Totals("CRC", new BigDecimal("100000"), 1),
                List.of(),
                new com.neoproc.financialagent.contract.payroll.RosterDiff(List.of(), List.of()),
                null, null);
        // Schema-valid: real envelopeId UUID, non-null issuerRunId, real hex SHA-256 audit.
        PayrollSubmitResult result = new PayrollSubmitResult(
                PayrollSubmitResult.SCHEMA,
                new EnvelopeMeta(UUID.randomUUID().toString(), BUSINESS_KEY, FIRM_ID, "es",
                        Instant.now(), "test", "test-run"),
                SubmitTask.forSalaries(PORTAL_ID, null, null, null),
                null, body,
                new com.neoproc.financialagent.contract.payroll.Audit(
                        "none", null, null, EnvelopeIo.sha256Hex("placeholder-fixture-payload")));
        EnvelopeIo.MAPPER.writeValue(tempDir.resolve("payroll-submit-result.v1.json").toFile(), result);
    }

    private String buildRequestJson(String envelopeId) throws Exception {
        PayrollSubmitRequest request = new PayrollSubmitRequest(
                "payroll-submit-request.v1",
                new EnvelopeMeta(envelopeId, BUSINESS_KEY, FIRM_ID, "es",
                        Instant.now(), "test-issuer", "run-" + envelopeId),
                SubmitTask.forSalaries(PORTAL_ID, null, null, null),
                null,
                new SubmitRequestBody(List.of(
                        new SubmitRequestBody.SubmitEmployee(
                                "E001", "Alice", new BigDecimal("500000"), Map.of()))),
                null);
        return MAPPER.writeValueAsString(request);
    }

    private static MessageHeaders headersWithReceiveCount(int n) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("Sqs_ApproximateReceiveCount", String.valueOf(n));
        return new MessageHeaders(raw);
    }
}
