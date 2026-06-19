package com.neoproc.financialagent.contract.bankstatement;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.payroll.Encryption;

/**
 * Agent-worker -&gt; Praxis. Reports the outcome of one Xero bank-statement
 * upload. The {@code task} block echoes the request verbatim for
 * correlation. The {@code result} field is a {@link ResultBody} object in
 * cleartext mode, or a {@code "vault:vN:"} ciphertext String if encrypted.
 *
 * <p>{@code businessKey} on {@link #envelope} MUST equal the request's,
 * verbatim — Praxis correlates the receive task on it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BankStatementUploadResult(
        EnvelopeMeta envelope,
        BankStatementTask task,
        Encryption encryption,
        Object result,
        Audit audit) {

    public static final String SCHEMA = "bank-statement-upload-result.v1";
}
