package com.neoproc.financialagent.worker.listener;

import com.neoproc.financialagent.contract.agent.AgentTask;
import com.neoproc.financialagent.contract.agent.AgentTaskRequest;
import com.neoproc.financialagent.contract.agent.AgentTaskResult;
import com.neoproc.financialagent.contract.agent.ArtifactRef;
import com.neoproc.financialagent.contract.agent.RunArtifacts;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.validation.SchemaValidator;
import com.neoproc.financialagent.worker.PortalRunService;
import com.neoproc.financialagent.worker.RunOutcome;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import com.neoproc.financialagent.worker.idempotency.IdempotencyStore;
import com.neoproc.financialagent.worker.portal.PortalDescriptorLoader;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumes {@code agent-task-request.v1} envelopes from the shared task queue
 * and runs whichever bot {@code task.botId} names.
 *
 * <p>This is the generic counterpart to the per-portal listeners: one queue
 * serves every automation, so onboarding a bot means shipping a descriptor and
 * an adapter — no new queue, no new schema, no {@code contract-api} republish,
 * and nothing for the BPM side to consume before it can dispatch.
 *
 * <p>The result reports where the run's output landed in S3 (folder plus
 * per-file URIs) so the consumer can download documents without knowing how a
 * run directory is laid out.
 *
 * <p>Idempotency follows the same contract as
 * {@link PayrollTaskListener}: a warm duplicate re-publishes the cached result
 * without re-running the bot, and a cold duplicate (cache lost to a restart)
 * reports {@code EXPIRED_DUPLICATE} rather than silently running the
 * automation a second time.
 */
/*
 * Opt-in, NOT matchIfMissing: this listener binds to a queue that does not yet
 * exist in any environment, and under QueueNotFoundStrategy.FAIL a listener on
 * a missing queue aborts the whole Spring context — taking the payroll
 * capture/submit listeners down with it (the hazard
 * PortalFlowsEnvironmentPostProcessor exists to prevent). Enable only where
 * the shared task queue has actually been provisioned, by setting
 * agent.worker.agent-task-enabled=true.
 */
@Component
@ConditionalOnProperty(name = "agent.worker.agent-task-enabled", havingValue = "true")
public class AgentTaskListener {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskListener.class);

    private final PortalRunService portalRunService;
    private final SqsRetryablePublisher publisher;
    private final IdempotencyStore idempotencyStore;
    private final String resultsQueue;

    public AgentTaskListener(PortalRunService portalRunService,
                             SqsRetryablePublisher publisher,
                             IdempotencyStore idempotencyStore,
                             @Value("${agent.worker.results-queue}") String resultsQueue) {
        this.portalRunService = portalRunService;
        this.publisher = publisher;
        this.idempotencyStore = idempotencyStore;
        this.resultsQueue = resultsQueue;
    }

    @SqsListener("${agent.worker.agent-task-queue}")
    public void onMessage(@Payload String rawMessage, @Header MessageHeaders headers)
            throws Exception {
        byte[] rawBytes = rawMessage.getBytes(StandardCharsets.UTF_8);
        // Validate the raw bytes, not the deserialised object: lenient
        // deserialization would silently drop an unknown field rather than
        // surfacing it as the contract violation it is.
        SchemaValidator.validate(rawBytes, SchemaValidator.AGENT_TASK_REQUEST);

        // EnvelopeIo.MAPPER, not a bare ObjectMapper: the envelope carries an
        // Instant, which a default mapper cannot read or write.
        AgentTaskRequest request = EnvelopeIo.MAPPER.readValue(rawBytes, AgentTaskRequest.class);
        String envelopeId = request.envelope().envelopeId();
        String businessKey = request.envelope().businessKey();
        String botId = request.task().botId();
        setupMdc(request);

        try {
            log.info("receive botId={} envelopeId={}", botId, envelopeId);

            Optional<Object> cached = idempotencyStore.lookup(envelopeId);
            if (cached.isPresent()) {
                log.warn("warm-cache duplicate envelopeId={} — re-publishing cached result", envelopeId);
                publishResult((AgentTaskResult) cached.get(), businessKey);
                return;
            }

            AgentTaskResult result;
            if (extractReceiveCount(headers) > 1) {
                log.warn("cold-cache duplicate envelopeId={} — emitting EXPIRED_DUPLICATE", envelopeId);
                result = failed(request, "EXPIRED_DUPLICATE",
                        "envelopeId previously processed but cached result lost (worker restart): "
                                + envelopeId);
            } else if (!PortalDescriptorLoader.exists(botId)) {
                // Redelivery cannot make an unknown bot resolvable, so this is
                // a terminal contract error rather than something to retry.
                log.error("unknown botId={} envelopeId={}", botId, envelopeId);
                result = failed(request, "SCHEMA_VIOLATION",
                        "no bot registered for botId '" + botId + "'");
            } else {
                result = executeRun(request);
            }

            idempotencyStore.cacheResult(envelopeId, result);
            publishResult(result, businessKey);
            log.info("result published envelopeId={} status={}", envelopeId, result.status());

        } catch (SqsRetryablePublisher.PublishFailedException pubFailed) {
            // Retries exhausted — throw so SQS redelivers. The warm-cache hit
            // on the next attempt re-publishes without re-running the bot.
            log.error("publish failed terminally envelopeId={} — throwing for SQS redelivery",
                    envelopeId, pubFailed);
            throw pubFailed;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("run failed envelopeId={}", envelopeId, e);
            AgentTaskResult result = failed(request, "UNCAUGHT_EXCEPTION",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            idempotencyStore.cacheResult(envelopeId, result);
            publishResult(result, businessKey);
        } finally {
            MDC.clear();
        }
    }

    // -----------------------------------------------------------------------

    private AgentTaskResult executeRun(AgentTaskRequest request) throws Exception {
        RunOutcome outcome = portalRunService.run(
                request.task().botId(), null, toBindings(request));

        List<ArtifactRef> files = outcome.artifacts().stream()
                .map(a -> new ArtifactRef(a.name(), a.uri(), a.bytes()))
                .toList();
        RunArtifacts artifacts = new RunArtifacts(outcome.artifactFolder(), files);

        String status = normaliseStatus(outcome.status());
        AgentTaskResult.TaskError error = "FAILED".equals(status)
                ? new AgentTaskResult.TaskError("UNEXPECTED",
                        "run ended with status " + outcome.status())
                : null;

        return new AgentTaskResult(
                AgentTaskResult.SCHEMA,
                resultEnvelope(request),
                request.task(),
                status,
                error,
                artifacts);
    }

    /**
     * Maps a run status onto the three the contract exposes. Statuses that mean
     * "did not complete as intended" ({@code MISMATCH}, {@code SHADOW_HALT})
     * collapse to FAILED rather than being passed through, so a consumer never
     * has to branch on a status the schema does not list.
     */
    private static String normaliseStatus(String runStatus) {
        if (runStatus == null) {
            return "FAILED";
        }
        return switch (runStatus) {
            case "SUCCESS", "CAPTURED" -> "SUCCESS";
            case "PARTIAL" -> "PARTIAL";
            default -> "FAILED";
        };
    }

    /**
     * Flattens {@code task.params} into the {@code params.*} bindings the run
     * service already understands, so a bot reads its inputs exactly as it does
     * from a CLI run.
     */
    private static Map<String, String> toBindings(AgentTaskRequest request) {
        Map<String, String> bindings = new LinkedHashMap<>();
        request.task().params().forEach((key, value) -> {
            if (value != null) {
                bindings.put("params." + key, String.valueOf(value));
            }
        });
        bindings.put("params.firmId", String.valueOf(request.envelope().firmId()));
        bindings.put("params.businessKey", request.envelope().businessKey());
        bindings.put("params.issuerRunId", request.envelope().issuerRunId());
        return bindings;
    }

    /**
     * Builds the result envelope. {@code businessKey} is copied verbatim from
     * the request — Praxis correlates on it, and a synthesised key means the
     * BPM waits forever.
     */
    private static EnvelopeMeta resultEnvelope(AgentTaskRequest request) {
        EnvelopeMeta in = request.envelope();
        return new EnvelopeMeta(
                UUID.randomUUID().toString(),
                in.businessKey(),
                in.firmId(),
                in.locale(),
                Instant.now(),
                "agent-worker/" + request.task().botId(),
                in.issuerRunId());
    }

    private AgentTaskResult failed(AgentTaskRequest request, String category, String message) {
        return new AgentTaskResult(
                AgentTaskResult.SCHEMA,
                resultEnvelope(request),
                request.task(),
                "FAILED",
                new AgentTaskResult.TaskError(category, message),
                RunArtifacts.none());
    }

    private void publishResult(AgentTaskResult result, String businessKey) {
        // A schema failure here is a worker bug; DLQ the message rather than
        // send the BPM something it cannot correlate or parse.
        SchemaValidator.validate(result, SchemaValidator.AGENT_TASK_RESULT);
        publisher.publish(
                resultsQueue,
                result,
                AgentTaskResult.SCHEMA,
                result.envelope().envelopeId(),
                businessKey);
    }

    private static void setupMdc(AgentTaskRequest request) {
        MDC.put("envelopeId", request.envelope().envelopeId());
        MDC.put("businessKey", request.envelope().businessKey());
        MDC.put("firmId", String.valueOf(request.envelope().firmId()));
        MDC.put("issuerRunId", String.valueOf(request.envelope().issuerRunId()));
    }

    private static int extractReceiveCount(MessageHeaders headers) {
        Object raw = headers.get("ApproximateReceiveCount");
        if (raw == null) {
            return 1;
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Exposed for tests that drive the listener without a broker. */
    static AgentTask taskOf(String botId, Map<String, Object> params) {
        return new AgentTask(botId, params);
    }
}
