package com.neoproc.financialagent.contract.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Per-spec §5: payloadSha256 lets the next consumer (BPM or the next
 * worker) re-verify the payload it received hasn't been tampered with.
 * The hash is computed over the cleartext result/request body BEFORE
 * encryption.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Audit(
        String manifestPath,
        String harSha256,
        String screenshotSha256,
        String payloadSha256) {}
