package com.neoproc.financialagent.contract.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayrollCaptureRequest(
        String schema,         // "payroll-capture-request.v1"
        EnvelopeMeta envelope,
        CaptureTask task,
        Encryption encryption,
        Object request,
        Audit audit) {

    public static final String SCHEMA = "payroll-capture-request.v1";
}
