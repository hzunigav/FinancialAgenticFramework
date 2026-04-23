package com.neoproc.financialagent.contract.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Cleartext body of payroll-submit-result.v1 result field. Carries the
 * status the BPM exclusive gateway routes on, the per-row submission
 * proof, and the {@link RosterDiff} that drives the lifecycle
 * subprocesses (register hires / deregister terminations).
 *
 * <p>The {@code rosterDiff} Java field is serialized as {@code roster_diff}
 * to match the schema convention used in the BPMN design doc.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmitResultBody(
        String status,                       // SUCCESS | PARTIAL | MISMATCH | FAILED
        PortalConfirmation portalConfirmation,
        Totals totals,
        List<SubmittedRow> submittedRows,
        @JsonProperty("roster_diff") RosterDiff rosterDiff,
        ErrorDetail errorDetail) {

    public SubmitResultBody {
        submittedRows = submittedRows == null ? List.of() : List.copyOf(submittedRows);
    }

    public record PortalConfirmation(String id, Instant issuedAt, String rawText) {}

    public record Totals(String currency, BigDecimal grandTotal, int updatedCount) {}

    public record SubmittedRow(String id, String displayName, BigDecimal salary) {}

    public record ErrorDetail(String errorClass, String category, String message) {}
}
