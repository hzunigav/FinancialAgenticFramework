package com.neoproc.financialagent.contract.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Which bot to run and what to run it with.
 *
 * @param botId  descriptor id of the bot, e.g. {@code "ccss-sicere-reports"}
 * @param params flat, bot-specific inputs. Open by design: the bot's own
 *               descriptor declares which keys are required, so onboarding a
 *               new automation never edits the shared contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentTask(String botId, Map<String, Object> params) {

    public AgentTask {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
