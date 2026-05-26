package com.neoproc.financialagent.worker.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neoproc.financialagent.common.crypto.EnvelopeCipher;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.payroll.EnvelopeStatus;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitRequest;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitResult;
import com.neoproc.financialagent.contract.payroll.RosterDiff;
import com.neoproc.financialagent.contract.payroll.SubmitResultBody;
import com.neoproc.financialagent.contract.payroll.SubmitTask;
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
 * Consumes {@code payroll-submit-request.v1} envelopes from the
 * portal-keyed submit queue, executes the portal interaction via
 * {@link PortalRunService}, and publishes a
 * {@code payroll-submit-result.v1} envelope to the shared results queue.
 *
 * <p>Recovery + idempotency contract per SqsMigrationPlan.md §6.3:
 * <ul>
 *   <li><b>Cache hit</b> (warm duplicate): re-publish the cached result
 *       envelope. Do not re-execute the portal.</li>
 *   <li><b>Cache miss + {@code ApproximateReceiveCount > 1}</b> (cold
 *       duplicate, e.g. worker restart): emit a synthetic
 *       {@code EXPIRED_DUPLICATE} failure. Do not re-execute the portal —
 *       portals like Hacienda are not idempotent and a re-run could
 *       create a duplicate filing.</li>
 *   <li><b>Cache miss + receiveCount == 1</b> (fresh delivery): run the
 *       portal, cache the result, publish.</li>
 * </ul>
 *
 * <p>If the result publish fails after 3 retries, the listener throws —
 * SQS redelivers after the visibility timeout, the cache hit on next
 * attempt re-publishes the original result.
 */
@Component
public class PayrollTaskListener {

    private static final Logger log = LoggerFactory.getLogger(PayrollTaskListener.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    private final PortalRunService portalRunService;
    private final IdempotencyStore idempotencyStore;
    private final SqsRetryablePublisher publisher;

    @Value("${portal.id}")
    private String portalId;

    @Value("${agent.worker.results-queue}")
    private String resultsQueue;

    public PayrollTaskListener(PortalRunService portalRunService,
                                IdempotencyStore idempotencyStore,
                                SqsRetryablePublisher publisher) {
        this.portalRunService = portalRunService;
        this.idempotencyStore = idempotencyStore;
        this.publisher = publisher;
    }

    @SqsListener("${agent.worker.submit-queue}")
    public void onSubmitRequest(@Payload String body,
                                @Header(name = "envelopeId", required = false) String envelopeIdHeader,
                                MessageHeaders headers) throws Exception {
        byte[] rawBytes = body.getBytes(StandardCharsets.UTF_8);

        // Defend against malformed envelopes from upstream — schema-validate
        // raw bytes (catches additionalProperties / enum violations that
        // lenient deserialization would silently drop).
        SchemaValidator.validate(rawBytes, SchemaValidator.SUBMIT_REQUEST);

        PayrollSubmitRequest request = MAPPER.readValue(rawBytes, PayrollSubmitRequest.class);
        String envelopeId = request.envelope().envelopeId();
        String businessKey = request.envelope().businessKey();
        setupMdc(request);

        try {
            log.info("receive portalId={} envelopeId={}", portalId, envelopeId);

            Optional<Object> cached = idempotencyStore.lookup(envelopeId);
            if (cached.isPresent()) {
                log.warn("warm-cache duplicate envelopeId={} — re-publishing cached result", envelopeId);
                publishResult((PayrollSubmitResult) cached.get(), businessKey);
                return;
            }

            PayrollSubmitResult result;
            if (extractReceiveCount(headers) > 1) {
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
            // Retries exhausted. Throw so SQS redelivers; the cache hit on
            // the next attempt will re-publish without portal re-execution.
            log.error("publish failed terminally envelopeId={} — throwing for SQS redelivery", envelopeId, pubFailed);
            throw pubFailed;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("run failed envelopeId={}", envelopeId, e);
            PayrollSubmitResult failed = buildFailedResult(request, "UNCAUGHT_EXCEPTION",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            idempotencyStore.cacheResult(envelopeId, failed);
            publishResult(failed, businessKey);
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
        return buildMinimalResult(request, outcome.status(), outcome.artifactUri());
    }

    private void publishResult(PayrollSubmitResult result, String businessKey) {
        // Schema-validate the outgoing envelope. A failure is a worker bug —
        // we'd rather DLQ the message than send Praxis something it cannot
        // correlate.
        SchemaValidator.validate(result, SchemaValidator.SUBMIT_RESULT);
        publisher.publish(
                resultsQueue,
                result,
                PayrollSubmitResult.SCHEMA,
                result.envelope().envelopeId(),
                businessKey);
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
                null, zeroTotals(), List.of(),
                new RosterDiff(List.of(), List.of()),
                new SubmitResultBody.ErrorDetail(
                        PayrollTaskListener.class.getName(), category, message),
                null);
        return wrapResult(request, body, null);
    }

    private PayrollSubmitResult buildMinimalResult(PayrollSubmitRequest request,
                                                    String status,
                                                    String artifactUri) {
        SubmitResultBody body = new SubmitResultBody(
                status, null, zeroTotals(), List.of(),
                new RosterDiff(List.of(), List.of()), null, null);
        return wrapResult(request, body, artifactUri);
    }

    // Schema requires totals; null fails validation. Zero placeholder for
    // FAILED/minimal envelopes that don't have real reconciliation data.
    private static SubmitResultBody.Totals zeroTotals() {
        return new SubmitResultBody.Totals("CRC", java.math.BigDecimal.ZERO, 0);
    }

    private PayrollSubmitResult wrapResult(PayrollSubmitRequest request,
                                            SubmitResultBody body,
                                            String artifactUri) {
        long firmId = request.envelope().firmId();
        EnvelopeCipher cipher = EnvelopeIo.defaultCipher();
        EnvelopeIo.EncryptedPayload encrypted = EnvelopeIo.encryptBody(body, cipher, firmId);

        // payroll-submit-result.v1 declares additionalProperties: false on the
        // task block and does not allow period / planilla — those belong only
        // to the request side. Echoing the request's task verbatim into a
        // result (e.g. for FAILED / DUPLICATE_ENVELOPE / minimal envelopes)
        // would fail schema validation and DLQ the message, leaving Praxis
        // stuck on Wait. Build a result-side task that carries only the
        // schema-allowed identifiers.
        SubmitTask reqTask = request.task();
        SubmitTask task = reqTask != null
                ? SubmitTask.forSalaries(
                        reqTask.targetPortal(),
                        reqTask.sourceCaptureEnvelopeId(),
                        reqTask.clientIdentifier(),
                        null, null)
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
                encrypted.result(),
                new Audit(artifactUri != null ? artifactUri : "none",
                        null, null, encrypted.payloadSha256()));
    }

    // -----------------------------------------------------------------------

    /**
     * Extracts SQS {@code ApproximateReceiveCount} from the message headers.
     * Spring Cloud AWS surfaces SQS system attributes as {@code Sqs_*}
     * headers. A receiveCount of 1 means first delivery; anything >1 is a
     * redelivery, which signals (in combination with a cache miss) that we
     * processed this envelopeId before but lost the cached result.
     */
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
            if (task.planilla() != null) {
                if (task.planilla().id() != null)
                    b.put("params.planillaId", task.planilla().id());
                if (task.planilla().name() != null)
                    b.put("params.planillaName", task.planilla().name());
            }
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
