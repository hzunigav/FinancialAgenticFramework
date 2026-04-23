package com.neoproc.financialagent.contract.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.Map;

/**
 * One employee inside the cleartext result/request payload. {@code id}
 * is the cédula as displayed (the matcher normalizes); {@code attributes}
 * is an open extension map for portal-specific extras (start date,
 * occupation code, risk class, etc.) that future operations need without
 * forcing a schema bump.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmployeeRow(
        String id,
        String displayName,
        BigDecimal grossSalary,
        Map<String, String> attributes) {

    public EmployeeRow {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
