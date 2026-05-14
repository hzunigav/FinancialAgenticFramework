package com.neoproc.financialagent.worker.listener;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")  // SqsTemplate.send takes Consumer<SqsSendOptions<T>>; raw Consumer is fine for verify().
class SqsRetryablePublisherTest {

    @Mock
    SqsTemplate sqsTemplate;

    @Test
    void firstAttemptSuccessSkipsRetry() {
        SqsRetryablePublisher publisher = new SqsRetryablePublisher(sqsTemplate);

        publisher.publish("queue", "payload", "schema.v1", "env-1", "biz-1");

        verify(sqsTemplate, times(1)).send(any(Consumer.class));
    }

    @Test
    void transientFailureRetriesUntilSuccess() {
        AtomicInteger calls = new AtomicInteger(0);
        // First two attempts blow up, third succeeds.
        org.mockito.Mockito.doAnswer(invocation -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                throw new RuntimeException("transient SQS failure attempt=" + n);
            }
            return null;
        }).when(sqsTemplate).send(any(Consumer.class));

        SqsRetryablePublisher publisher = new SqsRetryablePublisher(sqsTemplate);
        publisher.publish("queue", "payload", "schema.v1", "env-2", "biz-2");

        assertEquals(3, calls.get());
        verify(sqsTemplate, times(3)).send(any(Consumer.class));
    }

    @Test
    void terminalFailureThrowsPublishFailedException() {
        RuntimeException underlying = new RuntimeException("AWS down");
        org.mockito.Mockito.doThrow(underlying).when(sqsTemplate).send(any(Consumer.class));

        SqsRetryablePublisher publisher = new SqsRetryablePublisher(sqsTemplate);

        SqsRetryablePublisher.PublishFailedException thrown = assertThrows(
                SqsRetryablePublisher.PublishFailedException.class,
                () -> publisher.publish("queue", "payload", "schema.v1", "env-3", "biz-3"));

        assertSame(underlying, thrown.getCause());
        verify(sqsTemplate, times(3)).send(any(Consumer.class));
    }
}
