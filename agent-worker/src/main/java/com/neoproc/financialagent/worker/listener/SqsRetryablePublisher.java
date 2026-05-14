package com.neoproc.financialagent.worker.listener;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Publishes a result envelope to the SQS results queue, wrapping the send
 * in a bounded retry-with-backoff loop. Belt-and-suspenders against the
 * silent-data-loss mode flagged in SqsMigrationPlan.md §6.3 — a transient
 * publish failure must NOT cause the worker's listener to ack the inbound
 * message and lose the result.
 *
 * <p>Three attempts with exponential backoff (1s / 2s / 4s). After the
 * third failure the caller's listener throws, SQS redelivers the inbound
 * after the visibility timeout, and the listener's idempotency-store
 * cache hit re-enters this publisher with the same cached payload.
 */
@Component
public class SqsRetryablePublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsRetryablePublisher.class);

    private static final long[] BACKOFF_MS = { 1_000L, 2_000L, 4_000L };

    private final SqsTemplate sqsTemplate;

    public SqsRetryablePublisher(SqsTemplate sqsTemplate) {
        this.sqsTemplate = sqsTemplate;
    }

    /**
     * @throws PublishFailedException if all 3 attempts fail. The cause is
     *         the last underlying exception from {@code SqsTemplate.send}.
     */
    public void publish(String queueName,
                        Object payload,
                        String schemaName,
                        String envelopeId,
                        String businessKey) {
        Exception last = null;
        for (int attempt = 1; attempt <= BACKOFF_MS.length; attempt++) {
            try {
                sqsTemplate.send(to -> to
                        .queue(queueName)
                        .payload(payload)
                        .header("schemaName", schemaName)
                        .header("envelopeId", envelopeId)
                        .header("businessKey", businessKey));
                if (attempt > 1) {
                    log.info("publish succeeded on attempt {} envelopeId={}", attempt, envelopeId);
                }
                return;
            } catch (Exception e) {
                last = e;
                log.warn("publish attempt {}/{} failed envelopeId={} cause={}",
                        attempt, BACKOFF_MS.length, envelopeId, e.toString());
                if (attempt < BACKOFF_MS.length) {
                    sleep(BACKOFF_MS[attempt - 1]);
                }
            }
        }
        throw new PublishFailedException(
                "SQS publish failed after " + BACKOFF_MS.length + " attempts for envelopeId=" + envelopeId,
                last);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during publish backoff", ie);
        }
    }

    /**
     * Thrown after retries are exhausted. Listener-level catch sites should
     * let this propagate so SQS redelivers and the cached-result recovery
     * path runs on the next attempt.
     */
    public static class PublishFailedException extends RuntimeException {
        public PublishFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
