package com.neoproc.financialagent.contract.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Routing metadata that appears unencrypted in every payroll envelope.
 * BPM uses this for correlation, dedup, and tenant scoping; the worker
 * never encrypts these fields.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnvelopeMeta(
        String envelopeId,
        String businessKey,
        long firmId,
        String locale,
        Instant createdAt,
        String issuer,
        String issuerRunId) {}
