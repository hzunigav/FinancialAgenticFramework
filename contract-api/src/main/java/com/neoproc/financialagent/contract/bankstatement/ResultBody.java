package com.neoproc.financialagent.contract.bankstatement;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Cleartext body of {@code bank-statement-upload-result.v1} (the
 * {@code result} field). Not a single boolean — it carries the per-check
 * verification, reconciliation, rejected rows, all errors, and diagnostics
 * so a human can troubleshoot from the HITL task alone.
 *
 * <p>Schema invariant (enforced by the result schema's if/then/else):
 * {@code errorCategory} is null on {@link BankStatementStatus#SUCCESS} and
 * non-null otherwise. {@code reconciliation} and {@code checks} are always
 * present — even on FAILED, where {@code reconciliation.importedLineCount}
 * is 0 and {@code checks} may be empty.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultBody(
        BankStatementStatus status,
        ErrorCategory errorCategory,
        String errorMessage,
        Boolean retryable,
        FailedStage failedStage,
        String xeroOrgMatched,
        String bankAccountMatched,
        Reconciliation reconciliation,
        List<Check> checks,
        List<RejectedRow> rejectedRows,
        List<ErrorItem> errors,
        Diagnostics diagnostics) {

    public ResultBody {
        checks = checks == null ? List.of() : List.copyOf(checks);
        rejectedRows = rejectedRows == null ? List.of() : List.copyOf(rejectedRows);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
