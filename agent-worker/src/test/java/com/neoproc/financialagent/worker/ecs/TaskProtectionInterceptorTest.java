package com.neoproc.financialagent.worker.ecs;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskProtectionInterceptorTest {

    private final List<Boolean> calls = new ArrayList<>();
    private final TaskProtectionInterceptor interceptor = new TaskProtectionInterceptor(calls::add);

    private static Message<Object> msg() {
        return MessageBuilder.withPayload((Object) "body").build();
    }

    @Test
    void protectsForTheDurationOfOneMessage() {
        Message<Object> m = msg();
        assertThat(interceptor.intercept(m)).isSameAs(m);
        interceptor.afterProcessing(m, null);

        assertThat(calls).containsExactly(true, false);
    }

    @Test
    void releasesEvenWhenTheListenerThrew() {
        Message<Object> m = msg();
        interceptor.intercept(m);
        interceptor.afterProcessing(m, new RuntimeException("portal blew up"));

        assertThat(calls).containsExactly(true, false);
        assertThat(interceptor.inFlight()).isZero();
    }

    @Test
    void overlappingMessagesHoldProtectionUntilTheLastOneFinishes() {
        Message<Object> capture = msg();
        Message<Object> submit = msg();

        interceptor.intercept(capture);
        interceptor.intercept(submit);
        interceptor.afterProcessing(capture, null);
        assertThat(calls).containsExactly(true);   // submit still running

        interceptor.afterProcessing(submit, null);
        assertThat(calls).containsExactly(true, false);
    }

    @Test
    void anUnmatchedAfterProcessingDoesNotGoNegative() {
        interceptor.afterProcessing(msg(), null);
        assertThat(interceptor.inFlight()).isZero();
        assertThat(calls).isEmpty();

        // and the next message still enables protection
        interceptor.intercept(msg());
        assertThat(calls).containsExactly(true);
    }
}
