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
 *
 * <p>The {@code review} field is populated only for MISMATCH / PARTIAL runs
 * and carries the structured diagnostics that the Praxis HITL task form renders
 * as buttons ({@code allowedActions}) and a signal list.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmitResultBody(
        String status,                       // SUCCESS | PARTIAL | MISMATCH | FAILED
        PortalConfirmation portalConfirmation,
        Totals totals,
        List<SubmittedRow> submittedRows,
        @JsonProperty("roster_diff") RosterDiff rosterDiff,
        ErrorDetail errorDetail,
        Review review) {

    public SubmitResultBody {
        submittedRows = submittedRows == null ? List.of() : List.copyOf(submittedRows);
    }

    public record PortalConfirmation(String id, Instant issuedAt, String rawText) {}

    public record Totals(String currency, BigDecimal grandTotal, int updatedCount) {}

    public record SubmittedRow(String id, String displayName, BigDecimal salary) {}

    public record ErrorDetail(String errorClass, String category, String message) {}

    /**
     * Operator-facing review block for MISMATCH / PARTIAL runs. Praxis
     * renders {@code signals} grouped by type and exposes {@code allowedActions}
     * as task-form buttons the reviewer clicks to proceed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Review(String severity, String summary, List<Signal> signals, List<String> allowedActions) {
        public Review {
            signals = signals == null ? List.of() : List.copyOf(signals);
            allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
        }
    }

    /**
     * One diagnostic signal within a {@link Review}. The {@code type}
     * discriminator determines which optional fields are populated:
     * <ul>
     *   <li>{@code TOTAL_GAP} — canonical, portal, gap</li>
     *   <li>{@code MISSING_FROM_PORTAL} — id, name, expectedSalary</li>
     *   <li>{@code MISSING_FROM_PAYROLL} — id, name, lastKnownSalary</li>
     * </ul>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Signal(
            String type,
            BigDecimal canonical,
            BigDecimal portal,
            BigDecimal gap,
            String id,
            String name,
            BigDecimal expectedSalary,
            BigDecimal lastKnownSalary) {}
}
