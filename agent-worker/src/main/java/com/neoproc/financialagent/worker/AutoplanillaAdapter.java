package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.common.domain.PayrollEmployeeRow;
import com.neoproc.financialagent.common.domain.PayrollSummary;
import com.neoproc.financialagent.contract.payroll.CaptureResultBody;
import com.neoproc.financialagent.contract.payroll.EmployeeRow;
import com.neoproc.financialagent.contract.payroll.EnvelopeStatus;
import com.neoproc.financialagent.contract.payroll.Period;
import com.neoproc.financialagent.contract.payroll.Planilla;
import com.neoproc.financialagent.worker.portal.AutoplanillaMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Capture-only at M3. Pulls the CCSS report into a typed
 * {@link PayrollSummary} on the manifest. Envelope emission is handled
 * by the {@link AbstractCaptureAdapter} base class — this adapter only
 * needs to describe the portal-specific content.
 */
final class AutoplanillaAdapter extends AbstractCaptureAdapter {

    @Override
    protected CaptureOutcome buildCaptureOutcome(Map<String, String> scraped,
                                                 List<Map<String, String>> scrapedRows,
                                                 Map<String, String> bindings,
                                                 PortalCredentials credentials,
                                                 RunManifest manifest) {
        String planilla = require(bindings, "params.planillaName");
        LocalDate fechaInicio = LocalDate.parse(require(bindings, "params.fechaInicio"));
        LocalDate fechaFinal = LocalDate.parse(require(bindings, "params.fechaFinal"));

        PayrollSummary summary = AutoplanillaMapper.toSummary(
                scraped, scrapedRows, planilla, fechaInicio, fechaFinal);
        manifest.scraped = summary;
        manifest.step("capture",
                "planilla=" + planilla
                        + ", total=" + summary.totalGrossSalaries()
                        + ", renta=" + summary.totalRenta()
                        + ", employees=" + summary.employeeCount()
                        + ", rows=" + summary.employees().size());

        CaptureResultBody body = new CaptureResultBody(
                EnvelopeStatus.SUCCESS,
                new CaptureResultBody.Totals(
                        "CRC",
                        summary.totalGrossSalaries(),
                        summary.totalRenta(),
                        summary.employeeCount()),
                summary.employees().stream()
                        .map(AutoplanillaAdapter::toContractRow)
                        .toList());

        String businessKey = fechaInicio.getYear() + "-"
                + String.format("%02d", fechaInicio.getMonthValue())
                + "::" + planilla;

        return new CaptureOutcome(
                "CAPTURED",
                body,
                "autoplanilla",
                businessKey,
                "agent-worker/autoplanilla",
                new Period(fechaInicio, fechaFinal),
                new Planilla(null, planilla));
    }

    private static EmployeeRow toContractRow(PayrollEmployeeRow r) {
        return new EmployeeRow(r.id(), r.name(), r.grossSalary(), Map.of());
    }
}
