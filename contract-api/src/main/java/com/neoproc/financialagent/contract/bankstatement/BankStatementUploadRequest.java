package com.neoproc.financialagent.contract.bankstatement;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.payroll.Encryption;

/**
 * Praxis -&gt; agent-worker. Instructs the agent to upload one pre-formatted
 * CSV bank statement into a specific Xero organisation via the Xero UI.
 *
 * <p>Reuses the shared {@link EnvelopeMeta}, {@link Encryption}, and
 * {@link Audit} records. The {@code request} field is polymorphic (matching
 * the payroll convention): a {@link FileBody} object in cleartext mode, or a
 * {@code "vault:vN:"} ciphertext String when {@code encryption} is present.
 *
 * <p>There is no top-level {@code schema} discriminator field (the schema
 * declares {@code additionalProperties:false} and does not define one);
 * {@link #SCHEMA} names the schema for {@code SchemaValidator} calls only.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BankStatementUploadRequest(
        EnvelopeMeta envelope,
        BankStatementTask task,
        Encryption encryption,
        Object request,
        Audit audit) {

    public static final String SCHEMA = "bank-statement-upload-request.v1";
}
