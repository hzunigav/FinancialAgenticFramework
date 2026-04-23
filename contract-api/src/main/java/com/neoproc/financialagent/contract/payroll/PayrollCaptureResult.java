package com.neoproc.financialagent.contract.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Top-level payroll-capture-result.v1 envelope. {@code result} is either
 * a {@link CaptureResultBody} (cleartext) or a {@code String} (Vault
 * ciphertext) — Jackson handles both via the {@code Object} type. When
 * encrypted, {@link #encryption()} is non-null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayrollCaptureResult(
        String schema,         // "payroll-capture-result.v1"
        EnvelopeMeta envelope,
        CaptureTask task,
        Encryption encryption,
        Object result,
        Audit audit) {

    public static final String SCHEMA = "payroll-capture-result.v1";
}
