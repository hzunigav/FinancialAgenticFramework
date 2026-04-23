package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.common.crypto.EnvelopeCipher;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitResult;
import com.neoproc.financialagent.contract.payroll.SubmitResultBody;
import com.neoproc.financialagent.contract.payroll.SubmitTask;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Template for submit-side portal adapters (write flows against CCSS
 * Sicere, INS RT-Virtual, Hacienda OVI, mock-payroll). Owns the
 * {@code payroll-submit-result.v1} envelope emission so each new submit
 * adapter describes only the portal-specific work — login, matching,
 * filling, scraping back — not the wire-format plumbing.
 *
 * <p>Subclasses implement a single hook, {@link #buildSubmitOutcome}, that
 * returns the status, the cleartext body, and the per-portal metadata
 * (target portal id, source-capture envelope id, optional client
 * identifier for shared-creds portals). Encryption, audit hashing, and
 * file write are handled here.
 *
 * <p>The {@code clientIdentifier} field is only populated for portals
 * whose descriptor declares {@code credentialScope: shared} — INS,
 * Hacienda, AutoPlanilla. Per-firm portals like CCSS Sicere leave it
 * {@code null} and the Jackson {@code @JsonInclude(NON_NULL)} on
 * {@link SubmitTask} keeps it off the wire.
 */
abstract class AbstractSubmitAdapter extends BaseAdapter {

    @Override
    public final String captureToManifest(Map<String, String> scraped,
                                          List<Map<String, String>> scrapedRows,
                                          Map<String, String> bindings,
                                          PortalCredentials credentials,
                                          RunManifest manifest) {
        SubmitOutcome outcome = buildSubmitOutcome(
                scraped, scrapedRows, bindings, credentials, manifest);
        emitSubmitEnvelope(outcome, bindings, manifest);
        return outcome.status();
    }

    /**
     * Portal-specific submit logic. Implementations compute the
     * reconciliation (grand-total vs canonical), build a
     * {@link SubmitResultBody}, print the HITL review output for
     * {@code MISMATCH} / {@code PARTIAL} runs, and return a
     * {@link SubmitOutcome} the envelope wrapper can serialize.
     */
    protected abstract SubmitOutcome buildSubmitOutcome(
            Map<String, String> scraped,
            List<Map<String, String>> scrapedRows,
            Map<String, String> bindings,
            PortalCredentials credentials,
            RunManifest manifest);

    /**
     * Fields the envelope wrapper needs from a concrete submit adapter.
     *
     * @param status                   {@code SUCCESS} / {@code PARTIAL} / {@code MISMATCH} / {@code FAILED}
     * @param body                     cleartext submit body — gets encrypted before write
     * @param targetPortalId           portal descriptor id (e.g., {@code "ccss-sicere"})
     * @param businessKey              Flowable correlation key
     * @param issuer                   short identifier for the producer
     * @param sourceCaptureEnvelopeId  envelopeId of the capture this submit derives from; null if driven from legacy params
     * @param clientIdentifier         shared-creds portals only; null for per-firm portals
     */
    protected record SubmitOutcome(
            String status,
            SubmitResultBody body,
            String targetPortalId,
            String businessKey,
            String issuer,
            String sourceCaptureEnvelopeId,
            String clientIdentifier) {}

    private void emitSubmitEnvelope(SubmitOutcome outcome,
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

        SubmitTask task = SubmitTask.forSalaries(
                outcome.targetPortalId(),
                outcome.sourceCaptureEnvelopeId(),
                outcome.clientIdentifier(),
                null, null);

        Audit audit = new Audit(
                "manifest.json",
                null, null,
                encrypted.payloadSha256());

        PayrollSubmitResult record = new PayrollSubmitResult(
                PayrollSubmitResult.SCHEMA,
                envelope,
                task,
                encrypted.meta(),
                encrypted.ciphertext(),
                audit);

        Path envelopeFile = runDir.resolve("payroll-submit-result.v1.json");
        EnvelopeIo.write(record, envelopeFile);
        manifest.step("envelope", envelopeFile.getFileName()
                + " (envelopeId=" + envelope.envelopeId() + ", encrypted)");
    }
}
