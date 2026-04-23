package com.neoproc.financialagent.contract.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Per-target roster reconciliation. Drives the BPM lifecycle
 * subprocesses described in PayrollOrchestrationFlow.md §5: when
 * {@code missingFromPortal} is non-empty the inclusive gateway routes
 * to register-hires; when {@code missingFromPayroll} is non-empty it
 * routes to deregister-terminations.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RosterDiff(
        List<MissingFromPortal> missingFromPortal,
        List<MissingFromPayroll> missingFromPayroll) {

    public RosterDiff {
        missingFromPortal = missingFromPortal == null ? List.of() : List.copyOf(missingFromPortal);
        missingFromPayroll = missingFromPayroll == null ? List.of() : List.copyOf(missingFromPayroll);
    }

    public boolean isEmpty() {
        return missingFromPortal.isEmpty() && missingFromPayroll.isEmpty();
    }

    /** In source payroll, not on target portal. Likely new-hire candidate. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MissingFromPortal(
            String id,
            String name,
            BigDecimal expectedSalary,
            Map<String, String> attributes) {

        public MissingFromPortal {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    /** On target portal, not in source payroll. Likely termination candidate. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MissingFromPayroll(
            String id,
            String displayName,
            BigDecimal lastKnownSalary) {}
}
