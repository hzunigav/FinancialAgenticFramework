package com.neoproc.financialagent.contract.payroll;

/**
 * Status vocabulary used in capture and submit result envelopes. Defined
 * as String constants (not an enum) so unknown values from the wire
 * deserialize cleanly — BPM treats unknowns as escalation per
 * PayrollOrchestrationFlow.md §4.
 */
public final class EnvelopeStatus {

    public static final String SUCCESS  = "SUCCESS";
    public static final String PARTIAL  = "PARTIAL";
    public static final String MISMATCH = "MISMATCH";
    public static final String FAILED   = "FAILED";

    private EnvelopeStatus() {}
}
