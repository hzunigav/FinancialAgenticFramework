package com.neoproc.financialagent.worker.listener;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PayrollCaptureListener {

    private static final Logger log = LoggerFactory.getLogger(PayrollCaptureListener.class);

    private final PortalRunService portalRunService;
    private final IdempotencyStore idempotencyStore;
    private final RabbitTemplate rabbitTemplate;

    @Value("${portal.id}")
    private String portalId;

    @Value("${agent.worker.results-exchange}")
    private String resultsExchange;

    @Value("${agent.worker.results-routing-key:}")
    private String resultsRoutingKey;

    public PayrollCaptureListener(PortalRunService portalRunService,
                                   IdempotencyStore idempotencyStore,
                                   RabbitTemplate rabbitTemplate) {
        this.portalRunService = portalRunService;
        this.idempotencyStore = idempotencyStore;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = "${agent.worker.capture-queue}",
                    errorHandler = "envelopeAwareErrorHandler")
    public void onCaptureRequest(PayrollCaptureRequest request, Message rawMessage) {
        String envelopeId = request.envelope().envelopeId();
        String businessKey = request.envelope().businessKey();

        setupMdc(request);
        log.info("receive portalId={} envelopeId={}", portalId, envelopeId);

        try {
            // Defend against malformed envelopes from upstream — schema-validate
            // the raw bytes (catches additionalProperties / enum violations that
            // lenient deserialization would silently drop).
            SchemaValidator.validate(rawMessage.getBody(), SchemaValidator.CAPTURE_REQUEST);

            PayrollCaptureResult result;

            if (!idempotencyStore.tryRecord(envelopeId)) {
                log.warn("duplicate envelopeId={} — acking without portal re-execution", envelopeId);
                result = buildFailedResult(request, "DUPLICATE_ENVELOPE",
                        "envelopeId already processed: " + envelopeId);
            } else {
                result = executeRun(request);
            }

            publishResult(result, businessKey);
            log.info("result published envelopeId={} resultStatus={}",
                    envelopeId, extractStatus(result));

        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("run failed envelopeId={}", envelopeId, e);
            try {
                publishResult(buildFailedResult(request, "UNCAUGHT_EXCEPTION",
                        e.getClass().getSimpleName() + ": " + e.getMessage()), businessKey);
            } catch (Exception pubEx) {
                log.error("cannot publish error result — routing to DLQ envelopeId={}", envelopeId, pubEx);
                throw new AmqpRejectAndDontRequeueException(
                        "Cannot publish error result for envelopeId=" + envelopeId, pubEx);
            }
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

    private void publishResult(PayrollCaptureResult result, String correlationId) {
        // Schema-validate the outgoing envelope. Failure surfaces a producer
        // bug at the wire boundary instead of letting Praxis silently reject.
        SchemaValidator.validate(result, SchemaValidator.CAPTURE_RESULT);
        rabbitTemplate.convertAndSend(resultsExchange, resultsRoutingKey, result, msg -> {
            msg.getMessageProperties().setCorrelationId(correlationId);
            msg.getMessageProperties().setContentType("application/json");
            msg.getMessageProperties().setType(PayrollCaptureResult.SCHEMA);
            return msg;
        });
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
            if (task.planilla() != null && task.planilla().name() != null)
                b.put("params.planillaName", task.planilla().name());
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
