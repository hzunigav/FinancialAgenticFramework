package com.neoproc.financialagent.worker.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.RabbitListenerErrorHandler;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Wired to every {@code @RabbitListener} in this package via
 * {@code errorHandler = "envelopeAwareErrorHandler"}.
 *
 * <p>Fires when the listener method throws <em>or</em> when Spring AMQP
 * cannot deserialize the raw message (MessageConversionException). In
 * both cases the raw AMQP {@link Message} is available, so we can
 * extract and log the {@code envelopeId} before nacking to the DLQ.
 * This ensures the envelope is grep-able in our logs even if
 * deserialization never succeeded — the analogue of the Praxis-side fix
 * documented in PraxisIntegrationHandoff §14.5.
 */
@Component("envelopeAwareErrorHandler")
public class EnvelopeAwareErrorHandler implements RabbitListenerErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeAwareErrorHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Object handleError(Message amqpMessage,
                               org.springframework.messaging.Message<?> message,
                               ListenerExecutionFailedException exception) {
        String envelopeId = extractEnvelopeId(amqpMessage.getBody());
        log.warn("message processing failed — nacking to DLQ envelopeId={} cause={}",
                envelopeId, rootCauseMessage(exception));
        throw new AmqpRejectAndDontRequeueException(
                "Processing failed for envelopeId=" + envelopeId, exception);
    }

    // -----------------------------------------------------------------------

    private static String extractEnvelopeId(byte[] body) {
        if (body == null || body.length == 0) {
            return "unknown";
        }
        try {
            Map<?, ?> raw = MAPPER.readValue(body, Map.class);
            Object envelope = raw.get("envelope");
            if (envelope instanceof Map<?, ?> envMap) {
                Object id = envMap.get("envelopeId");
                if (id instanceof String s) return s;
            }
        } catch (Exception ignored) {
            // malformed JSON — fall through to "unknown"
        }
        return "unknown";
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
