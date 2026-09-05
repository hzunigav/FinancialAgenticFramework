package com.neoproc.financialagent.contract.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;

/**
 * Generic result envelope for any agent-worker bot.
 *
 * <p>{@code envelope.businessKey} is the request's key echoed verbatim — the
 * worker never synthesises one, because BPM correlation depends on it matching
 * byte-for-byte.
 *
 * @param status one of {@code SUCCESS}, {@code PARTIAL}, {@code FAILED}
 * @param error  required whenever status is {@code FAILED}; optional on
 *               {@code PARTIAL} to say what is missing
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentTaskResult(
        String schema,         // "agent-task-result.v1"
        EnvelopeMeta envelope,
        AgentTask task,
        String status,
        TaskError error,
        RunArtifacts artifacts) {

    public static final String SCHEMA = "agent-task-result.v1";

    /**
     * @param category same taxonomy as {@code payroll-submit-result.v1}, so
     *                 existing consumer error branching keeps working
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskError(String category, String message) {}
}
