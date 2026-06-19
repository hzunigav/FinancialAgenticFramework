package com.neoproc.financialagent.contract.bankstatement;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One problem found during the run. A single statement may surface several;
 * the result's {@code errorCategory} is the primary/most-severe of these.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorItem(
        ErrorCategory category,
        String message,
        ErrorSeverity severity) {}
