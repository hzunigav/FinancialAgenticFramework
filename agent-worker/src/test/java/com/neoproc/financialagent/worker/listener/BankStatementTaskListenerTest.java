package com.neoproc.financialagent.worker.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neoproc.financialagent.contract.bankstatement.BankStatementStatus;
import com.neoproc.financialagent.contract.bankstatement.BankStatementTask;
import com.neoproc.financialagent.contract.bankstatement.BankStatementUploadRequest;
import com.neoproc.financialagent.contract.bankstatement.BankStatementUploadResult;
import com.neoproc.financialagent.contract.bankstatement.Check;
import com.neoproc.financialagent.contract.bankstatement.CheckName;
import com.neoproc.financialagent.contract.bankstatement.ErrorCategory;
import com.neoproc.financialagent.contract.bankstatement.FileBody;
import com.neoproc.financialagent.contract.bankstatement.Reconciliation;
import com.neoproc.financialagent.contract.bankstatement.ResultBody;
import com.neoproc.financialagent.contract.bankstatement.TargetSystem;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.worker.PortalRunService;
import com.neoproc.financialagent.worker.RunOutcome;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import com.neoproc.financialagent.worker.idempotency.InMemoryIdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessageHeaders;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

import java.nio.file.Path;

/**
 * Listener-level tests for the bank-statement (Xero) flow — the same four
 * recovery paths as {@link PayrollTaskListenerTest}: fresh run, warm-cache
 * re-publish, cold-cache {@code EXPIRED_DUPLICATE} (mapped to UNKNOWN, portal
 * NOT re-run), and terminal publish failure → throw. Mocks
 * {@link PortalRunService}; default cleartext cipher.
 */
class BankStatementTaskListenerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String BUSINESS_KEY = "NEOPROCSOCIEDADANONIMA::920336542::USD::20260601-20260617";
    private static final long FIRM_ID = 1L;
    private static final String PORTAL_ID = "xero";
    private static final String RESULTS_QUEUE = "test-financeagent-bankstatement-results";
    private static final String SHA64 =
            "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";

    private PortalRunService portalRunService;
    private SqsRetryablePublisher publisher;
    private InMemoryIdempotencyStore idempotencyStore;
    private BankStatementTaskListener listener;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        portalRunService = mock(PortalRunService.class);
        publisher = mock(SqsRetryablePublisher.class);
        idempotencyStore = new InMemoryIdempotencyStore();

        listener = new BankStatementTaskListener(portalRunService, idempotencyStore, publisher);
        ReflectionTestUtils.setField(listener, "portalId", PORTAL_ID);
        ReflectionTestUtils.setField(listener, "resultsQueue", RESULTS_QUEUE);

        writeMockResultFile();
        lenient().when(portalRunService.runBankStatement(eq(PORTAL_ID), any(BankStatementUploadRequest.class), anyMap()))
                .thenReturn(new RunOutcome(tempDir, "SUCCESS"));
    }

    @Test
    void freshDeliveryRunsUploadAndPublishesResult() throws Exception {
        String envelopeId = UUID.randomUUID().toString();
        listener.onUploadRequest(buildRequestJson(envelopeId), envelopeId, headers(1));

        verify(portalRunService, times(1)).runBankStatement(eq(PORTAL_ID), any(), anyMap());
        BankStatementUploadResult published = capturePublished();
        assertEquals(BUSINESS_KEY, published.envelope().businessKey());
        assertTrue(idempotencyStore.lookup(envelopeId).isPresent());
    }

    @Test
    void warmCacheDuplicateRePublishesWithoutReExecution() throws Exception {
        String envelopeId = UUID.randomUUID().toString();
        listener.onUploadRequest(buildRequestJson(envelopeId), envelopeId, headers(1));
        listener.onUploadRequest(buildRequestJson(envelopeId), envelopeId, headers(2));

        verify(portalRunService, times(1)).runBankStatement(eq(PORTAL_ID), any(), anyMap());
        verify(publisher, times(2)).publish(eq(RESULTS_QUEUE), any(),
                eq(BankStatementUploadResult.SCHEMA), anyString(), eq(BUSINESS_KEY));
    }

    @Test
    void coldCacheDuplicateEmitsFailedWithoutReExecution() throws Exception {
        String envelopeId = UUID.randomUUID().toString();
        listener.onUploadRequest(buildRequestJson(envelopeId), envelopeId, headers(2));

        verify(portalRunService, never()).runBankStatement(any(), any(), any());
        BankStatementUploadResult published = capturePublished();
        ResultBody body = assertInstanceOf(ResultBody.class, published.result());
        assertEquals(BankStatementStatus.FAILED, body.status());
        assertEquals(ErrorCategory.UNKNOWN, body.errorCategory());
        assertEquals(BUSINESS_KEY, published.envelope().businessKey());
        assertTrue(idempotencyStore.lookup(envelopeId).isPresent());
    }

    @Test
    void terminalPublishFailureThrowsForSqsRedelivery() throws Exception {
        doThrow(new SqsRetryablePublisher.PublishFailedException("retries exhausted",
                new RuntimeException("SQS down")))
                .when(publisher).publish(anyString(), any(), anyString(), anyString(), anyString());

        String envelopeId = UUID.randomUUID().toString();
        assertThrows(SqsRetryablePublisher.PublishFailedException.class,
                () -> listener.onUploadRequest(buildRequestJson(envelopeId), envelopeId, headers(1)));
    }

    // --- helpers ------------------------------------------------------------

    private BankStatementUploadResult capturePublished() {
        ArgumentCaptor<BankStatementUploadResult> captor =
                ArgumentCaptor.forClass(BankStatementUploadResult.class);
        verify(publisher).publish(eq(RESULTS_QUEUE), captor.capture(),
                eq(BankStatementUploadResult.SCHEMA), anyString(), eq(BUSINESS_KEY));
        return captor.getValue();
    }

    private void writeMockResultFile() throws Exception {
        ResultBody body = new ResultBody(
                BankStatementStatus.SUCCESS, null, null, false, null,
                "Neoproc Sociedad Anonima", "920336542 (USD)",
                new Reconciliation("USD", 2, 2, "1279.50", "1279.50",
                        "11211.00", "11211.00", "12490.50", "12490.50", null),
                List.of(Check.passed(CheckName.ROW_COUNT, "2", "2")),
                List.of(), List.of(), null);
        BankStatementUploadResult result = new BankStatementUploadResult(
                new EnvelopeMeta(UUID.randomUUID().toString(), BUSINESS_KEY, FIRM_ID, "es",
                        Instant.now(), "agent-worker/xero", "run-test"),
                task(), null, body, new Audit(null, null, null, SHA64));
        EnvelopeIo.MAPPER.writeValue(
                tempDir.resolve("bank-statement-upload-result.v1.json").toFile(), result);
    }

    private String buildRequestJson(String envelopeId) throws Exception {
        String inline = Base64.getEncoder().encodeToString(
                "Date,Amount,Payee,Description,Reference\n".getBytes(StandardCharsets.UTF_8));
        BankStatementUploadRequest request = new BankStatementUploadRequest(
                new EnvelopeMeta(envelopeId, BUSINESS_KEY, FIRM_ID, "es",
                        Instant.now(), "praxis-bpm/bank-statement-process", "run-" + envelopeId),
                task(), null,
                new FileBody(FileBody.FileRef.inline(SHA64, inline)),
                new Audit(null, null, null, SHA64));
        return MAPPER.writeValueAsString(request);
    }

    private static BankStatementTask task() {
        return new BankStatementTask(
                BankStatementTask.TYPE, BankStatementTask.OPERATION, TargetSystem.XERO,
                "e1f2a3b4-c5d6-4789-9abc-def012345678", "Neoproc Sociedad Anonima", "!0X0!!",
                "NEOPROCSOCIEDADANONIMA", "Banco Nacional", "920336542",
                "CR05015202001026284066", "USD",
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-17"),
                "NEOPROCSOCIEDADANONIMA_920336542_USD_2026060120260617.csv",
                2, "1279.50", "11211.00", "12490.50");
    }

    private static MessageHeaders headers(int receiveCount) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("Sqs_ApproximateReceiveCount", String.valueOf(receiveCount));
        return new MessageHeaders(raw);
    }
}
