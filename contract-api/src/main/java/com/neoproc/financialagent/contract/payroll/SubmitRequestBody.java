package com.neoproc.financialagent.contract.payroll;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Cleartext body of payroll-submit-request.v1 request field. The list of
 * employees to submit to a target portal — typically derived by BPM
 * from the upstream capture result, optionally filtered or modified
 * (e.g. only include employees whose salary changed since the last
 * submission).
 */
public record SubmitRequestBody(List<SubmitEmployee> employees) {

    public SubmitRequestBody {
        employees = employees == null ? List.of() : List.copyOf(employees);
    }

    public record SubmitEmployee(
            String id,
            String displayName,
            BigDecimal salary,
            Map<String, String> attributes) {

        public SubmitEmployee {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
