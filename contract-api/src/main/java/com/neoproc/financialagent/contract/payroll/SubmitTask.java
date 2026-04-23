package com.neoproc.financialagent.contract.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * task block of payroll-submit-request.v1 and payroll-submit-result.v1.
 * Identifies which target portal the work is for and which capture
 * envelope it derives from (so BPM and audit can trace cause-and-effect).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmitTask(
        String type,                    // PAYROLL_SUBMIT
        String operation,               // SUBMIT_SALARIES | SUBMIT_RENTA_DECLARATION
        String targetPortal,            // ccss-sicere | ins-rt-virtual | hacienda-ovi | mock-payroll
        String sourceCaptureEnvelopeId,
        String clientIdentifier,        // shared-creds portals only; null for per-firm portals
        Period period,
        Planilla planilla) {

    public static SubmitTask forSalaries(String targetPortal,
                                         String sourceCaptureEnvelopeId,
                                         Period period,
                                         Planilla planilla) {
        return new SubmitTask("PAYROLL_SUBMIT", "SUBMIT_SALARIES",
                targetPortal, sourceCaptureEnvelopeId, null, period, planilla);
    }

    /**
     * Factory for shared-creds portals (INS, Hacienda, AutoPlanilla) where
     * the envelope must carry the portal-side identifier that picks the
     * right client after NeoProc's shared login.
     */
    public static SubmitTask forSalaries(String targetPortal,
                                         String sourceCaptureEnvelopeId,
                                         String clientIdentifier,
                                         Period period,
                                         Planilla planilla) {
        return new SubmitTask("PAYROLL_SUBMIT", "SUBMIT_SALARIES",
                targetPortal, sourceCaptureEnvelopeId, clientIdentifier, period, planilla);
    }
}
