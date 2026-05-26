package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.contract.payroll.CaptureResultBody;
import com.neoproc.financialagent.contract.payroll.EnvelopeStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Capture-side adapter for CCSS Sicere.
 *
 * <p>Today this adapter serves as the foundation for the login-probe path
 * (businessKey {@code probe::*}) and as a skeleton for future CCSS report
 * capture (pulling planilla data from Sicere for reconciliation). The probe
 * path is handled entirely by {@link PortalRunService#runProbe} and never
 * reaches this adapter; the adapter exists so the registry in
 * {@link PortalRunService#newAdapter} can route capture requests correctly
 * once report capture is implemented.
 *
 * <p>When CCSS report capture is added, override {@link #buildCaptureOutcome}
 * to scrape the employer planilla selector page and return a populated
 * {@link CaptureResultBody}. The descriptor ({@code ccss-sicere-capture.yaml})
 * already authenticates and lands on the post-login dashboard; add navigation
 * steps there to reach the report view.
 */
final class CcssSicereCaptureAdapter extends AbstractCaptureAdapter {

    @Override
    protected CaptureOutcome buildCaptureOutcome(Map<String, String> scraped,
                                                  List<Map<String, String>> scrapedRows,
                                                  Map<String, String> bindings,
                                                  PortalCredentials credentials,
                                                  RunManifest manifest) {
        String clientId = bindings.getOrDefault("params.clientIdentifier", "unknown");
        manifest.step("capture", "stub; clientIdentifier=" + clientId
                + " (report capture not yet implemented)");

        CaptureResultBody body = new CaptureResultBody(
                EnvelopeStatus.SUCCESS,
                new CaptureResultBody.Totals("CRC", BigDecimal.ZERO, null, 0),
                List.of());

        String businessKey = bindings.getOrDefault(
                "params.businessKey",
                "ccss-sicere::" + bindings.get("runtime.runId"));

        return new CaptureOutcome(
                "CAPTURED",
                body,
                "ccss-sicere",
                businessKey,
                "agent-worker/ccss-sicere",
                null,
                null);
    }
}
