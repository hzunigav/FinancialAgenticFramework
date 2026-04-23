package com.neoproc.financialagent.common.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Typed capture of a CCSS-style payroll report pulled from a third-party
 * portal (e.g., AutoPlanilla). Carries both the payroll-wide totals
 * ({@code totalGrossSalaries}, {@code totalRenta}, {@code employeeCount})
 * and the per-employee row list ({@code employees}). The totals are the
 * QA-match handle against the government portal; the row list is the
 * input for a downstream INS-style submit flow.
 *
 * <p>{@code employees} may be empty for portals that only expose totals.
 */
public record PayrollSummary(
        String planillaName,
        LocalDate fechaInicio,
        LocalDate fechaFinal,
        BigDecimal totalGrossSalaries,
        BigDecimal totalRenta,
        int employeeCount,
        List<PayrollEmployeeRow> employees) {

    public PayrollSummary {
        employees = employees == null ? List.of() : List.copyOf(employees);
    }
}
