package com.neoproc.financialagent.contract.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayrollSubmitRequest(
        String schema,         // "payroll-submit-request.v1"
        EnvelopeMeta envelope,
        SubmitTask task,
        Encryption encryption,
        Object request,
        Audit audit) {

    public static final String SCHEMA = "payroll-submit-request.v1";
}
