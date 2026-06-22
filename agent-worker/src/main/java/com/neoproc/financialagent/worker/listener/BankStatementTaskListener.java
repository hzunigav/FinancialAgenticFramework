package com.neoproc.financialagent.worker.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neoproc.financialagent.common.crypto.EnvelopeCipher;
import com.neoproc.financialagent.contract.bankstatement.BankStatementStatus;
import com.neoproc.financialagent.contract.bankstatement.BankStatementTask;
import com.neoproc.financialagent.contract.bankstatement.BankStatementUploadRequest;
import com.neoproc.financialagent.contract.bankstatement.BankStatementUploadResult;
import com.neoproc.financialagent.contract.bankstatement.ErrorCategory;
import com.neoproc.financialagent.contract.bankstatement.ErrorItem;
import com.neoproc.financialagent.contract.bankstatement.ErrorSeverity;
import com.neoproc.financialagent.contract.bankstatement.Reconciliation;
import com.neoproc.financialagent.contract.bankstatement.ResultBody;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.validation.SchemaValidator;
import com.neoproc.financialagent.worker.PortalRunService;
import com.neoproc.financialagent.worker.RunOutcome;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import com.neoproc.financialagent.worker.idempotency.IdempotencyStore;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumes {@code bank-statement-upload-request.v1} envelopes from the Xero
 * task queue, executes the upload via {@link PortalRunService#runBankStatement},
 * and publishes a {@code bank-statement-upload-result.v1} to the results queue.
 *
 * <p>Mechanical analog of {@link PayrollTaskListener} — same recovery /
 * idempotency contract (warm-cache re-publish, cold-cache
 * {@code EXPIRED_DUPLICATE}, fresh run otherwise), same businessKey-passthrough
 * and schema-validate-on-publish guarantees. Gated on
 * {@code agent.worker.bankstatement-enabled} (set by the descriptor's
 * {@code flows: [bankstatement]}) so only a {@code PORTAL_ID=xero} worker
 * registers it.
 */
@Component
@ConditionalOnProperty(name = "agent.worker.bankstatement-enabled", havingValue = "true")
public class BankStatementTaskListener {

    private static final Logger log = LoggerFactory.getLogger(BankStatementTaskListener.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final PortalRunService portalRunService;
    private final IdempotencyStore idempotencyStore;
    private final SqsRetryablePublisher publisher;

    @Value("${portal.id}")
    private String portalId;

    @Value("${agent.worker.bankstatement-results-queue}")
    private String resultsQueue;

    public BankStatementTaskListener(PortalRunService portalRunService,
                                     IdempotencyStore idempotencyStore,
                                     SqsRetryablePublisher publisher) {
        this.portalRunService = portalRunService;
        this.idempotencyStore = idempotencyStore;
        this.publisher = publisher;
    }

    @SqsListener("${agent.worker.bankstatement-task-queue}")
    public void onUploadRequest(@Payload String body,
                                @Header(name = "envelopeId", required = false) String envelopeIdHeader,
                                MessageHeaders headers) throws Exception {
        byte[] rawBytes = body.getBytes(StandardCharsets.UTF_8);
        SchemaValidator.validate(rawBytes, SchemaValidator.BANK_STATEMENT_UPLOAD_REQUEST);

        BankStatementUploadRequest request = MAPPER.readValue(rawBytes, BankStatementUploadRequest.class);
        String envelopeId = request.envelope().envelopeId();
        String businessKey = request.envelope().businessKey();
        setupMdc(request);

        try {
            log.info("receive portalId={} envelopeId={}", portalId, envelopeId);

            Optional<Object> cached = idempotencyStore.lookup(envelopeId);
            if (cached.isPresent()) {
                log.warn("warm-cache duplicate envelopeId={} — re-publishing cached result", envelopeId);
                publishResult((BankStatementUploadResult) cached.get(), businessKey);
                return;
            }

            BankStatementUploadResult result;
            if (extractReceiveCount(headers) > 1) {
                log.warn("cold-cache duplicate envelopeId={} (receiveCount>1, no cached result) — EXPIRED_DUPLICATE",
                        envelopeId);
                result = buildFailedResult(request,
                        "envelopeId previously processed but cached result lost (worker restart): " + envelopeId);
            } else {
                result = executeRun(request);
            }

            idempotencyStore.cacheResult(envelopeId, result);
            publishResult(result, businessKey);
            log.info("result published envelopeId={} resultStatus={}", envelopeId, extractStatus(result));

        } catch (SqsRetryablePublisher.PublishFailedException pubFailed) {
            log.error("publish failed terminally envelopeId={} — throwing for SQS redelivery", envelopeId, pubFailed);
            throw pubFailed;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("run failed envelopeId={}", envelopeId, e);
            BankStatementUploadResult failed = buildFailedResult(request,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            idempotencyStore.cacheResult(envelopeId, failed);
            publishResult(failed, businessKey);
        } finally {
            MDC.clear();
        }
    }

    // -----------------------------------------------------------------------

    private BankStatementUploadResult executeRun(BankStatementUploadRequest request) throws Exception {
        RunOutcome outcome = portalRunService.runBankStatement(portalId, request, extractBindings(request));
        Path resultFile = outcome.runDir().resolve("bank-statement-upload-result.v1.json");
        if (Files.exists(resultFile)) {
            return EnvelopeIo.read(resultFile, BankStatementUploadResult.class);
        }
        // SHADOW_HALT or a crash before the adapter emitted — synthesize a FAILED.
        return buildFailedResult(request, "no result envelope emitted (status=" + outcome.status() + ")");
    }

    private void publishResult(BankStatementUploadResult result, String businessKey) {
        SchemaValidator.validate(result, SchemaValidator.BANK_STATEMENT_UPLOAD_RESULT);
        publisher.publish(
                resultsQueue,
                result,
                BankStatementUploadResult.SCHEMA,
                result.envelope().envelopeId(),
                businessKey);
    }

    /**
     * Builds a {@code FAILED} result for listener-level errors (duplicate /
     * uncaught). Maps to {@code UNKNOWN} (the bank-statement enum has no
     * listener-specific categories) with a descriptive message, and echoes the
     * request's task + a valid reconciliation ({@code importedLineCount=0}).
     */
    private BankStatementUploadResult buildFailedResult(BankStatementUploadRequest request, String message) {
        BankStatementTask task = request.task();
        ResultBody body = new ResultBody(
                BankStatementStatus.FAILED,
                ErrorCategory.UNKNOWN,
                message,
                false,
                null,
                null, null,
                reconciliationFromTask(task),
                List.of(), List.of(),
                List.of(new ErrorItem(ErrorCategory.UNKNOWN, message, ErrorSeverity.ERROR)),
                null);
        return wrapResult(request, body);
    }

    private static Reconciliation reconciliationFromTask(BankStatementTask task) {
        return new Reconciliation(
                task.currency(), task.expectedRowCount(), 0,
                task.expectedNetMovement(), null,
                task.expectedOpeningBalance(), null,
                task.expectedClosingBalance(), null, null);
    }

    private BankStatementUploadResult wrapResult(BankStatementUploadRequest request, ResultBody body) {
        long firmId = request.envelope().firmId();
        EnvelopeCipher cipher = EnvelopeIo.defaultCipher();
        EnvelopeIo.EncryptedPayload encrypted = EnvelopeIo.encryptBody(body, cipher, firmId);

        EnvelopeMeta envelope = new EnvelopeMeta(
                UUID.randomUUID().toString(),
                request.envelope().businessKey(),
                firmId,
                request.envelope().locale(),
                Instant.now(),
                "agent-worker/" + portalId,
                request.envelope().issuerRunId());

        // Bank-statement AuditBundle permits ONLY payloadSha256.
        Audit audit = new Audit(null, null, null, encrypted.payloadSha256());

        return new BankStatementUploadResult(
                envelope, request.task(), encrypted.meta(), encrypted.result(), audit);
    }

    // -----------------------------------------------------------------------

    static int extractReceiveCount(MessageHeaders headers) {
        Object v = headers.get("Sqs_ApproximateReceiveCount");
        if (v == null) v = headers.get("ApproximateReceiveCount");
        if (v == null) return 1;
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static Map<String, String> extractBindings(BankStatementUploadRequest request) {
        EnvelopeMeta env = request.envelope();
        Map<String, String> b = new LinkedHashMap<>();
        b.put("params.firmId", String.valueOf(env.firmId()));
        if (env.businessKey() != null) b.put("params.businessKey", env.businessKey());
        if (env.issuerRunId() != null) b.put("params.issuerRunId", env.issuerRunId());
        return b;
    }

    private static void setupMdc(BankStatementUploadRequest request) {
        EnvelopeMeta env = request.envelope();
        if (env == null) return;
        if (env.issuerRunId() != null) MDC.put("issuerRunId", env.issuerRunId());
        MDC.put("firmId", String.valueOf(env.firmId()));
        if (env.businessKey() != null) MDC.put("businessKey", env.businessKey());
        if (env.envelopeId() != null) MDC.put("envelopeId", env.envelopeId());
    }

    private static String extractStatus(BankStatementUploadResult result) {
        Object body = result.result();
        if (body instanceof ResultBody rb) return rb.status().name();
        // Cleartext bodies round-trip through JSON as a Map (the field is Object),
        // so read the status from it directly — only a genuine ciphertext String
        // is "ENCRYPTED" (logging only; doesn't affect the published envelope).
        if (body instanceof java.util.Map<?, ?> m && m.get("status") != null) {
            return String.valueOf(m.get("status"));
        }
        return "ENCRYPTED";
    }
}
