package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.common.crypto.EnvelopeCipher;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.CaptureResultBody;
import com.neoproc.financialagent.contract.payroll.CaptureTask;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.payroll.PayrollCaptureResult;
import com.neoproc.financialagent.contract.payroll.Period;
import com.neoproc.financialagent.contract.payroll.Planilla;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Template for capture-side portal adapters (source-of-truth pulls such as
 * AutoPlanilla). Owns the {@code payroll-capture-result.v1} envelope
 * emission so subclasses only describe the portal-specific content.
 *
 * <p>Subclasses implement a single hook, {@link #buildCaptureOutcome}, that
 * returns the status, the cleartext body, the portal identifier, and the
 * period/planilla metadata. Everything downstream — encryption, envelope
 * metadata, audit hashing, file write — is handled here, so a new capture
 * adapter is typically 40-50 lines of portal-specific code rather than
 * ~130 with envelope boilerplate.
 */
abstract class AbstractCaptureAdapter extends BaseAdapter {

    @Override
    public final String captureToManifest(Map<String, String> scraped,
                                          List<Map<String, String>> scrapedRows,
                                          Map<String, String> bindings,
                                          PortalCredentials credentials,
                                          RunManifest manifest) {
        CaptureOutcome outcome = buildCaptureOutcome(
                scraped, scrapedRows, bindings, credentials, manifest);
        emitCaptureEnvelope(outcome, bindings, manifest);
        return outcome.status();
    }

    /**
     * Portal-specific body construction. Implementations:
     * <ul>
     *   <li>Map {@code scraped} + {@code scrapedRows} into a
     *       {@link CaptureResultBody}.</li>
     *   <li>Stash a typed domain record on the manifest (if useful) and
     *       call {@code manifest.step(...)} for observability.</li>
     *   <li>Return a {@link CaptureOutcome} with all the fields the
     *       envelope wrapper needs.</li>
     * </ul>
     * Envelope emission is always SUCCESS-status for captures today —
     * the method returns the run status to write onto the manifest
     * (typically {@code "CAPTURED"}).
     */
    protected abstract CaptureOutcome buildCaptureOutcome(
            Map<String, String> scraped,
            List<Map<String, String>> scrapedRows,
            Map<String, String> bindings,
            PortalCredentials credentials,
            RunManifest manifest);

    /**
     * Fields the envelope wrapper needs from a concrete adapter.
     *
     * @param status       run status to record on the manifest (e.g., {@code "CAPTURED"})
     * @param body         cleartext capture body — gets encrypted before write
     * @param sourcePortalId  portal descriptor id (e.g., {@code "autoplanilla"})
     * @param businessKey  Flowable correlation key, format {@code "<period>::<planillaId-or-name>"}
     * @param issuer       short identifier for the producer (e.g., {@code "agent-worker/autoplanilla"})
     * @param period       date range the capture covers
     * @param planilla     identifier or name of the planilla captured
     */
    protected record CaptureOutcome(
            String status,
            CaptureResultBody body,
            String sourcePortalId,
            String businessKey,
            String issuer,
            Period period,
            Planilla planilla) {}

    private void emitCaptureEnvelope(CaptureOutcome outcome,
                                     Map<String, String> bindings,
                                     RunManifest manifest) {
        long firmId = Long.parseLong(require(bindings, "params.firmId"));
        Path runDir = Path.of(require(bindings, "runtime.runDir"));
        String runId = require(bindings, "runtime.runId");

        EnvelopeCipher cipher = EnvelopeIo.defaultCipher();
        EnvelopeIo.EncryptedPayload encrypted = EnvelopeIo.encryptBody(
                outcome.body(), cipher, firmId);

        EnvelopeMeta envelope = new EnvelopeMeta(
                UUID.randomUUID().toString(),
                outcome.businessKey(),
                firmId,
                "es",
                Instant.now(),
                outcome.issuer(),
                runId);

        CaptureTask task = CaptureTask.of(
                outcome.sourcePortalId(),
                outcome.period(),
                outcome.planilla());

        Audit audit = new Audit(
                "manifest.json",
                null, null,
                encrypted.payloadSha256());

        PayrollCaptureResult record = new PayrollCaptureResult(
                PayrollCaptureResult.SCHEMA,
                envelope,
                task,
                encrypted.meta(),
                encrypted.ciphertext(),
                audit);

        Path envelopeFile = runDir.resolve("payroll-capture-result.v1.json");
        EnvelopeIo.write(record, envelopeFile);
        manifest.step("envelope", envelopeFile.getFileName()
                + " (envelopeId=" + envelope.envelopeId() + ", encrypted)");
    }
}
