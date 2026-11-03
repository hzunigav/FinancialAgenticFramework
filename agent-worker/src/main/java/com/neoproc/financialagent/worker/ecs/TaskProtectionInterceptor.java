package com.neoproc.financialagent.worker.ecs;

import io.awspring.cloud.sqs.listener.interceptor.MessageInterceptor;
import org.springframework.messaging.Message;

/**
 * Holds ECS task scale-in protection while any SQS message is being processed,
 * across every listener in the process (a payroll worker consumes both its
 * capture and submit queues).
 *
 * <p>Registered on the container factory, so it covers every
 * {@code @SqsListener} without each listener having to remember it. SCA runs
 * {@link #afterProcessing} whether the listener returned or threw.
 *
 * <p>Protection is enabled on the first in-flight message and released when
 * the last one finishes — not toggled per message, so two overlapping
 * messages can't have the first one's release unprotect the second.
 */
public final class TaskProtectionInterceptor implements MessageInterceptor<Object> {

    private final TaskProtection protection;
    private int inFlight;

    public TaskProtectionInterceptor(TaskProtection protection) {
        this.protection = protection;
    }

    @Override
    public synchronized Message<Object> intercept(Message<Object> message) {
        if (inFlight++ == 0) {
            protection.set(true);
        }
        return message;
    }

    @Override
    public synchronized void afterProcessing(Message<Object> message, Throwable t) {
        if (inFlight > 0 && --inFlight == 0) {
            protection.set(false);
        }
    }

    synchronized int inFlight() {
        return inFlight;
    }
}
