package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.common.crypto.EnvelopeCipher;
import com.neoproc.financialagent.common.domain.PayrollEmployeeRow;
import com.neoproc.financialagent.common.domain.PayrollSummary;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.CaptureResultBody;
import com.neoproc.financialagent.contract.payroll.CaptureTask;
import com.neoproc.financialagent.contract.payroll.EmployeeRow;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.payroll.EnvelopeStatus;
import com.neoproc.financialagent.contract.payroll.PayrollCaptureResult;
import com.neoproc.financialagent.contract.payroll.Period;
import com.neoproc.financialagent.contract.payroll.Planilla;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import com.neoproc.financialagent.worker.portal.AutoplanillaMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Capture-only at M3. Pulls the CCSS report into a typed
 * {@link PayrollSummary} on the manifest, AND emits a
 * {@code payroll-capture-result.v1.json} envelope next to the manifest
 * so downstream BPM Service Tasks (or the local mock-payroll submit
 * agent) can consume the rows as input.
 */
final class AutoplanillaAdapter implements PortalAdapter {

    @Override
    public String captureToManifest(Map<String, String> scraped,
                                    List<Map<String, String>> scrapedRows,
                                    Map<String, String> bindings,
                                    PortalCredentials credentials,
                                    RunManifest manifest) {
        String planilla = require(bindings, "params.planillaName");
        LocalDate fechaInicio = LocalDate.parse(require(bindings, "params.fechaInicio"));
        LocalDate fechaFinal = LocalDate.parse(require(bindings, "params.fechaFinal"));
        long firmId = Long.parseLong(require(bindings, "params.firmId"));
        Path runDir = Path.of(require(bindings, "runtime.runDir"));
        String runId = require(bindings, "runtime.runId");

        PayrollSummary summary = AutoplanillaMapper.toSummary(
                scraped, scrapedRows, planilla, fechaInicio, fechaFinal);
        manifest.scraped = summary;
        manifest.step("capture",
                "planilla=" + planilla
                        + ", total=" + summary.totalGrossSalaries()
                        + ", renta=" + summary.totalRenta()
                        + ", employees=" + summary.employeeCount()
                        + ", rows=" + summary.employees().size());

        writeEnvelope(summary, planilla, fechaInicio, fechaFinal, firmId, runId, runDir, manifest);
        return "CAPTURED";
    }

    private void writeEnvelope(PayrollSummary summary,
                               String planilla,
                               LocalDate fechaInicio,
                               LocalDate fechaFinal,
                               long firmId,
                               String runId,
                               Path runDir,
                               RunManifest manifest) {
        EnvelopeCipher cipher = EnvelopeIo.defaultCipher();

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

        EnvelopeIo.EncryptedPayload encrypted = EnvelopeIo.encryptBody(body, cipher, firmId);

        EnvelopeMeta envelope = new EnvelopeMeta(
                UUID.randomUUID().toString(),
                fechaInicio.getYear() + "-" + String.format("%02d", fechaInicio.getMonthValue())
                        + "::" + planilla,
                firmId,
                "es",
                Instant.now(),
                "agent-worker/autoplanilla",
                runId);

        CaptureTask task = CaptureTask.of(
                "autoplanilla",
                new Period(fechaInicio, fechaFinal),
                new Planilla(null, planilla));

        Audit audit = new Audit(
                "manifest.json",
                null, null,
                encrypted.payloadSha256());

        PayrollCaptureResult envelopeRecord = new PayrollCaptureResult(
                PayrollCaptureResult.SCHEMA,
                envelope,
                task,
                encrypted.meta(),
                encrypted.ciphertext(),
                audit);

        Path envelopeFile = runDir.resolve("payroll-capture-result.v1.json");
        EnvelopeIo.write(envelopeRecord, envelopeFile);
        manifest.step("envelope", envelopeFile.getFileName()
                + " (envelopeId=" + envelope.envelopeId() + ", encrypted)");
    }

    private static EmployeeRow toContractRow(PayrollEmployeeRow r) {
        return new EmployeeRow(r.id(), r.name(), r.grossSalary(), Map.of());
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
