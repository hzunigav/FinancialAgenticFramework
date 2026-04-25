package com.neoproc.financialagent.worker.listener;

import com.neoproc.financialagent.common.crypto.EnvelopeCipher;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.payroll.EnvelopeStatus;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitRequest;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitResult;
import com.neoproc.financialagent.contract.payroll.RosterDiff;
import com.neoproc.financialagent.contract.payroll.SubmitResultBody;
import com.neoproc.financialagent.contract.payroll.SubmitTask;
import com.neoproc.financialagent.worker.PortalRunService;
import com.neoproc.financialagent.worker.RunOutcome;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import com.neoproc.financialagent.worker.idempotency.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes {@code payroll-submit-request.v1} messages from the
 * portal-keyed task queue, executes the portal interaction via
 * {@link PortalRunService}, and publishes a
 * {@code payroll-submit-result.v1} envelope to the results exchange.
 *
 * <p>Ack/nack semantics ({@code spring.rabbitmq.listener.simple.acknowledge-mode=auto}
 * + {@code default-requeue-rejected=false}):
 * <ul>
 *   <li>Listener returns normally → Spring acks (message removed from queue).</li>
 *   <li>Listener throws any exception → Spring nacks with requeue=false
 *       → message flows to DLQ. This only happens when even the error-result
 *       publish fails; all other failures produce a {@code FAILED} result
 *       envelope before acking.</li>
 * </ul>
 */
@Component
public class PayrollTaskListener {

    private static final Logger log = LoggerFactory.getLogger(PayrollTaskListener.class);

    private final PortalRunService portalRunService;
    private final IdempotencyStore idempotencyStore;
    private final RabbitTemplate rabbitTemplate;

    @Value("${portal.id}")
    private String portalId;

    @Value("${agent.worker.results-exchange}")
    private String resultsExchange;

    @Value("${agent.worker.results-routing-key:}")
    private String resultsRoutingKey;

    public PayrollTaskListener(PortalRunService portalRunService,
                                IdempotencyStore idempotencyStore,
                                RabbitTemplate rabbitTemplate) {
        this.portalRunService = portalRunService;
        this.idempotencyStore = idempotencyStore;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = "${agent.worker.submit-queue}")
    public void onSubmitRequest(PayrollSubmitRequest request) {
        String envelopeId = request.envelope().envelopeId();
        String businessKey = request.envelope().businessKey();

        setupMdc(request);
        log.info("receive portalId={} envelopeId={}", portalId, envelopeId);

        try {
            PayrollSubmitResult result;

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

    private PayrollSubmitResult executeRun(PayrollSubmitRequest request)
            throws Exception {
        Map<String, String> bindings = extractBindings(request);
        RunOutcome outcome = portalRunService.run(portalId, request, bindings);

        Path resultFile = outcome.runDir().resolve("payroll-submit-result.v1.json");
        if (Files.exists(resultFile)) {
            return EnvelopeIo.read(resultFile, PayrollSubmitResult.class);
        }
        // Capture-only adapters and SHADOW_HALT runs don't write a submit-result file.
        return buildMinimalResult(request, outcome.status());
    }

    private void publishResult(PayrollSubmitResult result, String correlationId) {
        rabbitTemplate.convertAndSend(resultsExchange, resultsRoutingKey, result, msg -> {
            msg.getMessageProperties().setCorrelationId(correlationId);
            msg.getMessageProperties().setContentType("application/json");
            msg.getMessageProperties().setType(PayrollSubmitResult.SCHEMA);
            return msg;
        });
    }

    /**
     * Builds a {@code FAILED} result envelope for error and duplicate cases.
     * Uses the same cipher path as normal runs so Praxis can always decrypt
     * results uniformly.
     */
    private PayrollSubmitResult buildFailedResult(PayrollSubmitRequest request,
                                                   String category,
                                                   String message) {
        SubmitResultBody body = new SubmitResultBody(
                EnvelopeStatus.FAILED,
                null, null, List.of(),
                new RosterDiff(List.of(), List.of()),
                new SubmitResultBody.ErrorDetail(
                        PayrollTaskListener.class.getName(), category, message),
                null);
        return wrapResult(request, body);
    }

    private PayrollSubmitResult buildMinimalResult(PayrollSubmitRequest request, String status) {
        SubmitResultBody body = new SubmitResultBody(
                status, null, null, List.of(),
                new RosterDiff(List.of(), List.of()), null, null);
        return wrapResult(request, body);
    }

    private PayrollSubmitResult wrapResult(PayrollSubmitRequest request, SubmitResultBody body) {
        long firmId = request.envelope().firmId();
        EnvelopeCipher cipher = EnvelopeIo.defaultCipher();
        EnvelopeIo.EncryptedPayload encrypted = EnvelopeIo.encryptBody(body, cipher, firmId);

        SubmitTask task = request.task() != null
                ? request.task()
                : SubmitTask.forSalaries(portalId, null, null, null);

        return new PayrollSubmitResult(
                PayrollSubmitResult.SCHEMA,
                new EnvelopeMeta(
                        UUID.randomUUID().toString(),
                        request.envelope().businessKey(),
                        firmId,
                        request.envelope().locale(),
                        Instant.now(),
                        "agent-worker/" + portalId,
                        request.envelope().issuerRunId()),
                task,
                encrypted.meta(),
                encrypted.ciphertext(),
                new Audit("none", null, null, encrypted.payloadSha256()));
    }

    // -----------------------------------------------------------------------

    private static Map<String, String> extractBindings(PayrollSubmitRequest request) {
        EnvelopeMeta env = request.envelope();
        Map<String, String> b = new LinkedHashMap<>();
        b.put("params.firmId", String.valueOf(env.firmId()));
        if (env.businessKey() != null)  b.put("params.businessKey", env.businessKey());
        if (env.issuerRunId() != null)  b.put("params.issuerRunId", env.issuerRunId());
        SubmitTask task = request.task();
        if (task != null) {
            if (task.clientIdentifier() != null)
                b.put("params.clientIdentifier", task.clientIdentifier());
            if (task.period() != null) {
                b.put("params.from", task.period().from().toString());
                b.put("params.to",   task.period().to().toString());
            }
            if (task.planilla() != null && task.planilla().name() != null)
                b.put("params.planillaName", task.planilla().name());
        }
        return b;
    }

    private static void setupMdc(PayrollSubmitRequest request) {
        EnvelopeMeta env = request.envelope();
        if (env == null) return;
        if (env.issuerRunId() != null) MDC.put("issuerRunId", env.issuerRunId());
        MDC.put("firmId", String.valueOf(env.firmId()));
        if (env.businessKey() != null) MDC.put("businessKey", env.businessKey());
        if (env.envelopeId() != null)  MDC.put("envelopeId", env.envelopeId());
    }

    private static String extractStatus(PayrollSubmitResult result) {
        if (result.result() instanceof SubmitResultBody body) return body.status();
        return "ENCRYPTED";
    }
}
