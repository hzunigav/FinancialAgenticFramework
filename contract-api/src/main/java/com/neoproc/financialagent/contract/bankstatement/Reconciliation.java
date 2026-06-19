package com.neoproc.financialagent.contract.bankstatement;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * Expected-vs-observed reconciliation block on the result. Present even on
 * FAILED runs (where {@code importedLineCount} is 0). Monetary values are
 * decimal strings; null where the corresponding check was skipped (e.g. a
 * balance Praxis did not supply).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Reconciliation(
        String currency,
        int expectedRowCount,
        int importedLineCount,
        String expectedNetMovement,
        String observedNetMovement,
        String expectedOpeningBalance,
        String observedOpeningBalance,
        String expectedClosingBalance,
        String observedClosingBalance,
        DateRange statementDateRange) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DateRange(LocalDate from, LocalDate to) {}
}
