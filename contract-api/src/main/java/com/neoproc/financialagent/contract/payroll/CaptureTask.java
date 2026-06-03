package com.neoproc.financialagent.contract.payroll;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * task block of payroll-capture-result.v1. Identifies what the worker
 * was asked to do (capture from one source portal over one period).
 *
 * <p>Two mutually exclusive ways to name the planilla(s):
 * <ul>
 *   <li>{@code planilla} — a single planilla (legacy / single-planilla flows).</li>
 *   <li>{@code planillas} — 1..N planillas consolidated server-side into one
 *       report (AutoPlanilla multi-select). When present it supersedes
 *       {@code planilla}; selection is by stable id.</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(NON_NULL)} so the unused alternative — and any other
 * absent field — is omitted on the wire rather than serialized as
 * {@code null} (the {@code planillas} array schema has no null branch).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaptureTask(
        String type,           // PAYROLL_CAPTURE
        String operation,      // CAPTURE
        String sourcePortal,
        Period period,
        Planilla planilla,
        List<Planilla> planillas) {

    public static CaptureTask of(String sourcePortal, Period period, Planilla planilla) {
        return new CaptureTask("PAYROLL_CAPTURE", "CAPTURE", sourcePortal, period, planilla, null);
    }

    public static CaptureTask ofMulti(String sourcePortal, Period period, List<Planilla> planillas) {
        return new CaptureTask("PAYROLL_CAPTURE", "CAPTURE", sourcePortal, period, null, planillas);
    }
}
