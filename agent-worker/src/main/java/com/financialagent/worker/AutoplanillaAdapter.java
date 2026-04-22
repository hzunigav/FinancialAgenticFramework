package com.financialagent.worker;

import com.financialagent.common.credentials.PortalCredentials;
import com.financialagent.common.domain.PayrollSummary;
import com.financialagent.worker.portal.AutoplanillaMapper;

import java.time.LocalDate;
import java.util.Map;

/**
 * Capture-only at M3. Pulls the CCSS report data into a typed
 * {@link PayrollSummary} on the manifest. Read-Back lands at M3b when the
 * government portal provides the comparison counterpart.
 *
 * <p>Runtime inputs (planilla name, date range) are read from the
 * bindings map under the {@code params.*} namespace, populated by Agent
 * from {@code -Dparams.*} system properties.
 */
final class AutoplanillaAdapter implements PortalAdapter {

    @Override
    public String captureToManifest(Map<String, String> scraped,
                                    Map<String, String> bindings,
                                    PortalCredentials credentials,
                                    RunManifest manifest) {
        String planilla = require(bindings, "params.planillaName");
        LocalDate fechaInicio = LocalDate.parse(require(bindings, "params.fechaInicio"));
        LocalDate fechaFinal = LocalDate.parse(require(bindings, "params.fechaFinal"));

        PayrollSummary summary = AutoplanillaMapper.toSummary(
                scraped, planilla, fechaInicio, fechaFinal);
        manifest.scraped = summary;
        manifest.step("capture",
                "planilla=" + planilla + ", total=" + summary.totalGrossSalaries()
                        + ", employees=" + summary.employeeCount());
        return "CAPTURED";
    }

    private static String require(Map<String, String> bindings, String key) {
        String v = bindings.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "Missing runtime binding " + key
                            + " (pass via -D" + key + "=<value>)");
        }
        return v;
    }
}
