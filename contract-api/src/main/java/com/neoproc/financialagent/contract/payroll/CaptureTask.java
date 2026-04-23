package com.neoproc.financialagent.contract.payroll;

/**
 * task block of payroll-capture-result.v1. Identifies what the worker
 * was asked to do (capture from one specific source portal for one
 * planilla over one period).
 */
public record CaptureTask(
        String type,           // PAYROLL_CAPTURE
        String operation,      // CAPTURE
        String sourcePortal,
        Period period,
        Planilla planilla) {

    public static CaptureTask of(String sourcePortal, Period period, Planilla planilla) {
        return new CaptureTask("PAYROLL_CAPTURE", "CAPTURE", sourcePortal, period, planilla);
    }
}
