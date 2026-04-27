package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.contract.payroll.CaptureResultBody;
import com.neoproc.financialagent.contract.payroll.EmployeeRow;
import com.neoproc.financialagent.contract.payroll.EnvelopeStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Capture-side adapter for the mock payroll harness. Logs in, navigates to
 * /employees, and scrapes the current roster as the canonical payroll snapshot.
 * This simulates what AutoPlanilla does in production: read the employer's
 * current payroll state before the HITL review and CCSS submission.
 */
final class MockPayrollCaptureAdapter extends AbstractCaptureAdapter {

    @Override
    protected CaptureOutcome buildCaptureOutcome(Map<String, String> scraped,
                                                  List<Map<String, String>> scrapedRows,
                                                  Map<String, String> bindings,
                                                  PortalCredentials credentials,
                                                  RunManifest manifest) {
        List<EmployeeRow> employees = scrapedRows.stream()
                .map(row -> new EmployeeRow(
                        row.get("id"),
                        row.get("name"),
                        parseSalary(row.get("currentSalary")),
                        Map.of()))
                .toList();

        BigDecimal total = employees.stream()
                .map(EmployeeRow::grossSalary)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        manifest.step("capture",
                "employees=" + employees.size() + " total=" + total);

        CaptureResultBody body = new CaptureResultBody(
                EnvelopeStatus.SUCCESS,
                new CaptureResultBody.Totals("CRC", total, null, employees.size()),
                employees);

        String businessKey = bindings.getOrDefault(
                "params.businessKey",
                "mock-payroll::" + bindings.get("runtime.runId"));

        return new CaptureOutcome(
                "CAPTURED",
                body,
                "mock-payroll",
                businessKey,
                "agent-worker/mock-payroll",
                null,
                null);
    }

    private static BigDecimal parseSalary(String raw) {
        if (raw == null || raw.isBlank()) return BigDecimal.ZERO;
        return new BigDecimal(raw.replace(",", "").trim()).setScale(2, RoundingMode.HALF_UP);
    }
}
