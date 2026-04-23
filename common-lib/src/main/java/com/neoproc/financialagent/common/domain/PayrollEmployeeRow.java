package com.neoproc.financialagent.common.domain;

import java.math.BigDecimal;

/**
 * One row of a CCSS-style payroll report. Captures the fields needed to
 * later submit this employee's salary to a downstream portal (INS): the
 * cédula as displayed (hyphenated or not — the matcher normalizes),
 * full name as displayed, and the gross monthly income reported by
 * the source portal.
 */
public record PayrollEmployeeRow(String id, String name, BigDecimal grossSalary) {}
