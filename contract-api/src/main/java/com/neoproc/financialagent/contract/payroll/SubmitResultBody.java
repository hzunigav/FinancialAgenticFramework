package com.neoproc.financialagent.contract.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

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

    /**
     * Post-submit totals.
     *
     * <ul>
     *   <li>{@code grandTotal} — the post-submit grand total visible on the
     *       portal. The legacy reconciliation field; {@code MISMATCH} fires
     *       when this drifts from {@code expected}.</li>
     *   <li>{@code expected} — sum of every canonical-input salary the run
     *       intended to put on the portal. Surfaced as a process variable
     *       for BPMN gating: {@code expected != grandTotal} (or
     *       {@code != portalReported}) is the deterministic mismatch
     *       signal. Optional — older capture-only adapters leave it null.</li>
     *   <li>{@code portalReported} — the portal's "reported salaries"
     *       total (CCSS Sicere's {@code Total de Salarios Reportados}).
     *       Distinct from {@code grandTotal} on portals that distinguish
     *       between a working/draft total and a saved/reported total.
     *       Optional — populated only by per-client portals that expose
     *       the distinction.</li>
     * </ul>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Totals(String currency,
                         BigDecimal grandTotal,
                         int updatedCount,
                         BigDecimal expected,
                         BigDecimal portalReported) {

        public Totals(String currency, BigDecimal grandTotal, int updatedCount) {
            this(currency, grandTotal, updatedCount, null, null);
        }
    }

    public record SubmittedRow(String id, String displayName, BigDecimal salary) {}

    public record ErrorDetail(String errorClass, String category, String message) {}

    /**
     * Operator-facing review block for MISMATCH / PARTIAL runs. Praxis
     * renders {@code signals} grouped by type and exposes {@code allowedActions}
     * as task-form buttons the reviewer clicks to proceed.
     *
     * <p>{@code severity} is constrained to {@link #SEVERITIES} (the schema
     * enum {@code INFO|WARN|ERROR}) at construction time. This guards the
     * regression where the producer passed envelope status ("MISMATCH" /
     * "PARTIAL") into the severity slot — both are {@code String} in Java
     * so the wrong domain compiled silently. The compact constructor now
     * fails fast, before any malformed envelope reaches validate-on-publish.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Review(String severity, String summary, List<Signal> signals, List<String> allowedActions) {

        /** Schema-aligned enum values for {@code review.severity}. */
        public static final Set<String> SEVERITIES = Set.of("INFO", "WARN", "ERROR");

        public Review {
            if (severity == null || !SEVERITIES.contains(severity)) {
                throw new IllegalArgumentException(
                        "review.severity must be one of " + SEVERITIES + " (got: " + severity + ")");
            }
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
     *   <li>{@code NAME_DRIFT} — id, name (canonical), portalName. Cédula
     *       matched uniquely so the salary was applied; the names disagree
     *       and HITL should confirm it's the same person.</li>
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
            String portalName,
            BigDecimal expectedSalary,
            BigDecimal lastKnownSalary) {

        /**
         * Backwards-compatible constructor for the pre-{@code NAME_DRIFT}
         * call sites that don't carry a {@code portalName}. New call sites
         * should use the canonical 9-arg form.
         */
        public Signal(String type,
                      BigDecimal canonical,
                      BigDecimal portal,
                      BigDecimal gap,
                      String id,
                      String name,
                      BigDecimal expectedSalary,
                      BigDecimal lastKnownSalary) {
            this(type, canonical, portal, gap, id, name, null, expectedSalary, lastKnownSalary);
        }
    }
}
