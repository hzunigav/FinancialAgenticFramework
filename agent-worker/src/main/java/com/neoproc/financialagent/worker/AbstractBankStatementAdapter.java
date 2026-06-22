package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.common.crypto.EnvelopeCipher;
import com.neoproc.financialagent.contract.bankstatement.BankStatementStatus;
import com.neoproc.financialagent.contract.bankstatement.BankStatementTask;
import com.neoproc.financialagent.contract.bankstatement.BankStatementUploadRequest;
import com.neoproc.financialagent.contract.bankstatement.BankStatementUploadResult;
import com.neoproc.financialagent.contract.bankstatement.ResultBody;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Template for the Xero bank-statement upload adapter. Owns the
 * {@code bank-statement-upload-result.v1} envelope emission so the concrete
 * adapter describes only the portal-specific work (org switch, account
 * select, CSV import, read-back) and returns a {@link ResultBody}.
 *
 * <p>Parallels {@link AbstractSubmitAdapter} but for the bank-statement
 * contract. Differences that matter:
 * <ul>
 *   <li>The result {@code task} block is <b>echoed verbatim</b> from the
 *       inbound request (read off disk via the
 *       {@code params.source.bankStatementRequest} binding the run path
 *       sets) — the schema requires the same {@code expected*} fields on both
 *       sides, and the agent's checks compare against them.</li>
 *   <li>The bank-statement {@code AuditBundle} permits <b>only</b>
 *       {@code payloadSha256} — the other {@link Audit} fields stay null.</li>
 *   <li>{@code businessKey} is echoed verbatim from the request envelope —
 *       Praxis correlates the receive task on it.</li>
 * </ul>
 */
abstract class AbstractBankStatementAdapter extends BaseAdapter {

    private static final Logger log = LoggerFactory.getLogger(AbstractBankStatementAdapter.class);

    /** Binding the run path sets to the on-disk inbound request envelope. */
    static final String REQUEST_BINDING = "params.source.bankStatementRequest";

    @Override
    public final String captureToManifest(Map<String, String> scraped,
                                          List<Map<String, String>> scrapedRows,
                                          Map<String, String> bindings,
                                          PortalCredentials credentials,
                                          RunManifest manifest) {
        UploadOutcome outcome = buildUploadOutcome(bindings, credentials, manifest);
        emitResultEnvelope(outcome, bindings, manifest);
        return outcome.status().name();
    }

    /**
     * Portal-specific upload logic. Implementations switch org, select the
     * account, import the CSV, read back the imported line count + balances,
     * run the checks, and return the status + a {@link ResultBody}.
     */
    protected abstract UploadOutcome buildUploadOutcome(Map<String, String> bindings,
                                                        PortalCredentials credentials,
                                                        RunManifest manifest);

    /**
     * @param status the headline status (FAILED &gt; MISMATCH &gt; PARTIAL &gt; SUCCESS)
     * @param body   cleartext result body — encrypted before write when a cipher is active
     * @param issuer short producer identifier (e.g. {@code "agent-worker/xero"})
     */
    protected record UploadOutcome(BankStatementStatus status, ResultBody body, String issuer) {}

    private void emitResultEnvelope(UploadOutcome outcome,
                                    Map<String, String> bindings,
                                    RunManifest manifest) {
        Path runDir = Path.of(require(bindings, "runtime.runDir"));
        String runId = require(bindings, "runtime.runId");

        // Echo the inbound request's task + envelope identity verbatim.
        BankStatementUploadRequest request = EnvelopeIo.read(
                Path.of(require(bindings, REQUEST_BINDING)), BankStatementUploadRequest.class);
        EnvelopeMeta reqEnvelope = request.envelope();
        BankStatementTask task = request.task();
        long firmId = reqEnvelope.firmId();

        EnvelopeCipher cipher = EnvelopeIo.defaultCipher();
        EnvelopeIo.EncryptedPayload encrypted = EnvelopeIo.encryptBody(outcome.body(), cipher, firmId);

        // businessKey passthrough is load-bearing — Praxis drops any result
        // whose businessKey != the request's.
        String businessKey = bindings.getOrDefault("params.businessKey", reqEnvelope.businessKey());
        String issuerRunId = bindings.getOrDefault("params.issuerRunId", runId);

        EnvelopeMeta envelope = new EnvelopeMeta(
                UUID.randomUUID().toString(),
                businessKey,
                firmId,
                reqEnvelope.locale(),
                Instant.now(),
                outcome.issuer(),
                issuerRunId);

        // Bank-statement AuditBundle permits ONLY payloadSha256.
        Audit audit = new Audit(null, null, null, encrypted.payloadSha256());

        BankStatementUploadResult result = new BankStatementUploadResult(
                envelope, task, encrypted.meta(), encrypted.result(), audit);

        Path file = runDir.resolve("bank-statement-upload-result.v1.json");
        EnvelopeIo.write(result, file);

        manifest.envelopeId = envelope.envelopeId();
        manifest.businessKey = businessKey;
        MDC.put("envelopeId", envelope.envelopeId());
        MDC.put("businessKey", businessKey);
        log.info("bank-statement result emitted file={} status={}",
                file.getFileName(), outcome.status());
        manifest.step("envelope", file.getFileName()
                + " (envelopeId=" + envelope.envelopeId() + ", status=" + outcome.status() + ")");
    }
}
