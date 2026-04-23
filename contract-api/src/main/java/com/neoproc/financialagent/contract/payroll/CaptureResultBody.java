package com.neoproc.financialagent.contract.payroll;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cleartext body of payroll-capture-result.v1 result field. When the
 * envelope is encrypted, this gets serialized to JSON, the JSON is
 * encrypted to a {@code vault:vN:...} string, and only the string is
 * carried over the wire — but the deserialized shape on both ends is
 * still this record.
 */
public record CaptureResultBody(
        String status,            // SUCCESS | PARTIAL | MISMATCH | FAILED
        Totals totals,
        List<EmployeeRow> employees) {

    public CaptureResultBody {
        employees = employees == null ? List.of() : List.copyOf(employees);
    }

    public record Totals(
            String currency,
            BigDecimal grossSalaries,
            BigDecimal renta,
            int employeeCount) {}
}
