package com.neoproc.financialagent.contract.bankstatement;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Troubleshooting bundle powering the HITL evidence panel: the worker run
 * id, timing, the Xero URL + on-screen message, screenshot references
 * (S3 keys), and a CloudWatch log reference.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Diagnostics(
        String workerRunId,
        Integer attemptCount,
        Integer durationMs,
        String xeroUrl,
        String xeroMessage,
        List<String> screenshotRefs,
        String logRef) {

    public Diagnostics {
        screenshotRefs = screenshotRefs == null ? List.of() : List.copyOf(screenshotRefs);
    }
}
