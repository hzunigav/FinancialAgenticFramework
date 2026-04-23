package com.neoproc.financialagent.contract.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayrollSubmitResult(
        String schema,         // "payroll-submit-result.v1"
        EnvelopeMeta envelope,
        SubmitTask task,
        Encryption encryption,
        Object result,
        Audit audit) {

    public static final String SCHEMA = "payroll-submit-result.v1";
}
