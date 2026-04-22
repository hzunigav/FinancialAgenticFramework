package com.financialagent.common.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Typed capture of a CCSS-style payroll report summary pulled from a
 * third-party portal (e.g., AutoPlanilla). The two load-bearing fields
 * at M3 are {@code totalGrossSalaries} and {@code employeeCount}; the
 * date range and planilla name are carried along so future Read-Back
 * against a government portal can match on the same period.
 */
public record PayrollSummary(
        String planillaName,
        LocalDate fechaInicio,
        LocalDate fechaFinal,
        BigDecimal totalGrossSalaries,
        int employeeCount) {}
