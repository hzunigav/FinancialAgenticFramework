package com.neoproc.financialagent.common.domain;

import java.time.LocalDate;

/**
 * Placeholder domain record captured from the post-auth report page of the
 * current mock portal. Two fields are enough to exercise the Read-Back
 * pipeline end-to-end. Replaces itself with {@code PayrollData} once real
 * data is wired through {@code mcp-payroll-server} in a later milestone.
 */
public record ReportSnapshot(String username, LocalDate reportDate) {}
