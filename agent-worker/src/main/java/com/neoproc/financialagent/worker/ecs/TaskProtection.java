package com.neoproc.financialagent.worker.ecs;

/**
 * Marks the running ECS task as protected from scale-in (or releases it).
 *
 * <p>Implementations must never throw: losing protection costs at worst an
 * interrupted run, while an exception here would fail a portal run that had
 * nothing wrong with it.
 */
public interface TaskProtection {

    void set(boolean enabled);

    /** Used outside ECS (local dev, tests), where there is nothing to protect. */
    TaskProtection NONE = enabled -> { };
}
