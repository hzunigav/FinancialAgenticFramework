package com.neoproc.financialagent.worker.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neoproc.financialagent.common.crypto.EnvelopeCipher;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.CaptureResultBody;
import com.neoproc.financialagent.contract.payroll.CaptureTask;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.payroll.PayrollCaptureRequest;
import com.neoproc.financialagent.contract.payroll.PayrollCaptureResult;
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
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
 * Capture-flow analog of {@link PayrollTaskListener}. Same recovery
 * contract per SqsMigrationPlan.md §6.3 (warm-cache → re-publish,
 * cold-cache → EXPIRED_DUPLICATE, fresh → run portal).
 */
@Component
public class PayrollCaptureListener {

    private static final Logger log = LoggerFactory.getLogger(PayrollCaptureListener.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    private final PortalRunService portalRunService;
    private final IdempotencyStore idempotencyStore;
    private final SqsRetryablePublisher publisher;

    @Value("${portal.id}")
    private String portalId;

    @Value("${agent.worker.results-queue}")
    private String resultsQueue;

    public PayrollCaptureListener(PortalRunService portalRunService,
                                   IdempotencyStore idempotencyStore,
                                   SqsRetryablePublisher publisher) {
        this.portalRunService = portalRunService;
        this.idempotencyStore = idempotencyStore;
        this.publisher = publisher;
    }

    @SqsListener("${agent.worker.capture-queue}")
    public void onCaptureRequest(@Payload String body,
                                  @Header(name = "envelopeId", required = false) String envelopeIdHeader,
                                  MessageHeaders headers) throws Exception {
        byte[] rawBytes = body.getBytes(StandardCharsets.UTF_8);

        // Defend against malformed envelopes from upstream — schema-validate
        // raw bytes (catches additionalProperties / enum violations that
        // lenient deserialization would silently drop).
        SchemaValidator.validate(rawBytes, SchemaValidator.CAPTURE_REQUEST);

        PayrollCaptureRequest request = MAPPER.readValue(rawBytes, PayrollCaptureRequest.class);
        String envelopeId = request.envelope().envelopeId();
        String businessKey = request.envelope().businessKey();
        setupMdc(request);

        try {
            log.info("receive portalId={} envelopeId={}", portalId, envelopeId);

            Optional<Object> cached = idempotencyStore.lookup(envelopeId);
            if (cached.isPresent()) {
                log.warn("warm-cache duplicate envelopeId={} — re-publishing cached result", envelopeId);
                publishResult((PayrollCaptureResult) cached.get(), businessKey);
                return;
            }

            PayrollCaptureResult result;
            if (PayrollTaskListener.extractReceiveCount(headers) > 1) {
                log.warn("cold-cache duplicate envelopeId={} (receiveCount>1, no cached result) — emitting EXPIRED_DUPLICATE",
                        envelopeId);
                result = buildFailedResult(request, "EXPIRED_DUPLICATE",
                        "envelopeId previously processed but cached result lost (worker restart): " + envelopeId);
            } else {
                result = executeRun(request);
            }

            idempotencyStore.cacheResult(envelopeId, result);
            publishResult(result, businessKey);
            log.info("result published envelopeId={} resultStatus={}",
                    envelopeId, extractStatus(result));

        } catch (SqsRetryablePublisher.PublishFailedException pubFailed) {
            log.error("publish failed terminally envelopeId={} — throwing for SQS redelivery", envelopeId, pubFailed);
            throw pubFailed;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("run failed envelopeId={}", envelopeId, e);
            PayrollCaptureResult failed = buildFailedResult(request, "UNCAUGHT_EXCEPTION",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            idempotencyStore.cacheResult(envelopeId, failed);
            publishResult(failed, businessKey);
        } finally {
            MDC.clear();
        }
    }

    // -----------------------------------------------------------------------

    private PayrollCaptureResult executeRun(PayrollCaptureRequest request) throws Exception {
        Map<String, String> bindings = extractBindings(request);
        RunOutcome outcome = portalRunService.run(portalId, null, bindings);

        Path resultFile = outcome.runDir().resolve("payroll-capture-result.v1.json");
        if (Files.exists(resultFile)) {
            return EnvelopeIo.read(resultFile, PayrollCaptureResult.class);
        }
        return buildMinimalResult(request, outcome.status());
    }

    private void publishResult(PayrollCaptureResult result, String businessKey) {
        // Schema-validate the outgoing envelope. Failure surfaces a producer
        // bug at the wire boundary instead of letting Praxis silently reject.
        SchemaValidator.validate(result, SchemaValidator.CAPTURE_RESULT);
        publisher.publish(
                resultsQueue,
                result,
                PayrollCaptureResult.SCHEMA,
                result.envelope().envelopeId(),
                businessKey);
    }

    private PayrollCaptureResult buildFailedResult(PayrollCaptureRequest request,
                                                    String category,
                                                    String message) {
        log.warn("capture failed category={} message={}", category, message);
        return wrapResult(request, new CaptureResultBody("FAILED", zeroTotals(), List.of()));
    }

    private PayrollCaptureResult buildMinimalResult(PayrollCaptureRequest request, String status) {
        return wrapResult(request, new CaptureResultBody(status, zeroTotals(), List.of()));
    }

    // Schema requires totals to be a fully-populated object; null totals fails
    // validation even on FAILED envelopes. Zero placeholder is the minimum that
    // round-trips through validate-on-publish.
    private static CaptureResultBody.Totals zeroTotals() {
        return new CaptureResultBody.Totals("CRC", BigDecimal.ZERO, null, 0);
    }

    private PayrollCaptureResult wrapResult(PayrollCaptureRequest request, CaptureResultBody body) {
        long firmId = request.envelope().firmId();
        EnvelopeCipher cipher = EnvelopeIo.defaultCipher();
        EnvelopeIo.EncryptedPayload encrypted = EnvelopeIo.encryptBody(body, cipher, firmId);

        CaptureTask task = request.task() != null
                ? request.task()
                : CaptureTask.of(portalId, null, null);

        String locale = request.envelope().locale() != null ? request.envelope().locale() : "es";

        return new PayrollCaptureResult(
                PayrollCaptureResult.SCHEMA,
                new EnvelopeMeta(
                        UUID.randomUUID().toString(),
                        request.envelope().businessKey(),
                        firmId,
                        locale,
                        Instant.now(),
                        "agent-worker/" + portalId,
                        request.envelope().issuerRunId()),
                task,
                encrypted.meta(),
                encrypted.result(),
                new Audit("none", null, null, encrypted.payloadSha256()));
    }

    // -----------------------------------------------------------------------

    private static Map<String, String> extractBindings(PayrollCaptureRequest request) {
        EnvelopeMeta env = request.envelope();
        Map<String, String> b = new LinkedHashMap<>();
        b.put("params.firmId", String.valueOf(env.firmId()));
        if (env.businessKey() != null)  b.put("params.businessKey", env.businessKey());
        if (env.issuerRunId() != null)  b.put("params.issuerRunId", env.issuerRunId());
        CaptureTask task = request.task();
        if (task != null) {
            if (task.sourcePortal() != null)
                b.put("params.sourcePortal", task.sourcePortal());
            if (task.period() != null) {
                b.put("params.from", task.period().from().toString());
                b.put("params.to",   task.period().to().toString());
            }
            if (task.planilla() != null) {
                if (task.planilla().id() != null)
                    b.put("params.planillaId", task.planilla().id());
                if (task.planilla().name() != null)
                    b.put("params.planillaName", task.planilla().name());
            }
        }
        return b;
    }

    private static void setupMdc(PayrollCaptureRequest request) {
        EnvelopeMeta env = request.envelope();
        if (env == null) return;
        if (env.issuerRunId() != null) MDC.put("issuerRunId", env.issuerRunId());
        MDC.put("firmId", String.valueOf(env.firmId()));
        if (env.businessKey() != null) MDC.put("businessKey", env.businessKey());
        if (env.envelopeId() != null)  MDC.put("envelopeId", env.envelopeId());
    }

    private static String extractStatus(PayrollCaptureResult result) {
        if (result.result() instanceof CaptureResultBody body) return body.status();
        return "ENCRYPTED";
    }
}
