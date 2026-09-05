package com.neoproc.financialagent.contract.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;

/**
 * Generic launch envelope for any agent-worker bot, consumed off the shared
 * task queue. {@link AgentTask#botId()} selects the bot.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentTaskRequest(
        String schema,         // "agent-task-request.v1"
        EnvelopeMeta envelope,
        AgentTask task) {

    public static final String SCHEMA = "agent-task-request.v1";
}
