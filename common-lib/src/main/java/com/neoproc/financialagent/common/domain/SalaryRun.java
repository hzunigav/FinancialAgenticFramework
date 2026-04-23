package com.neoproc.financialagent.common.domain;

import java.math.BigDecimal;

/**
 * Sum-and-count snapshot of a salary-submission round. Compared record-
 * to-record by {@link com.neoproc.financialagent.common.verify.ReadBackVerifier}:
 * source side is what the agent submitted (sum of canonical salaries +
 * count of rows the agent intended to update); scraped side is what the
 * portal echoed back on its confirmation page. Any mismatch is surfaced
 * as MISMATCH on the run manifest and logged loudly — our current HITL
 * flag until M5 ships the full approval loop.
 */
public record SalaryRun(BigDecimal totalSalary, int employeeCount) {}
