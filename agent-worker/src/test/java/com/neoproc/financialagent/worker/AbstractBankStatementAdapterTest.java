package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.contract.bankstatement.BankStatementStatus;
import com.neoproc.financialagent.contract.bankstatement.BankStatementTask;
import com.neoproc.financialagent.contract.bankstatement.BankStatementUploadRequest;
import com.neoproc.financialagent.contract.bankstatement.BankStatementUploadResult;
import com.neoproc.financialagent.contract.bankstatement.Check;
import com.neoproc.financialagent.contract.bankstatement.CheckName;
import com.neoproc.financialagent.contract.bankstatement.ErrorCategory;
import com.neoproc.financialagent.contract.bankstatement.ErrorItem;
import com.neoproc.financialagent.contract.bankstatement.ErrorSeverity;
import com.neoproc.financialagent.contract.bankstatement.FailedStage;
import com.neoproc.financialagent.contract.bankstatement.Reconciliation;
import com.neoproc.financialagent.contract.bankstatement.ResultBody;
import com.neoproc.financialagent.contract.bankstatement.TargetSystem;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.validation.SchemaValidator;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the {@code bank-statement-upload-result.v1} emission in
 * {@link AbstractBankStatementAdapter}: a schema-valid envelope, the inbound
 * task echoed verbatim, and businessKey passthrough — exercised without a
 * browser via a stub that returns a fixed {@link ResultBody}.
 */
class AbstractBankStatementAdapterTest {

    private static final String SHA64 =
            "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";
    private static final String BUSINESS_KEY =
            "NEOPROCSOCIEDADANONIMA::920336542::USD::20260601-20260617";

    @Test
    @DisplayName("SUCCESS: emits a schema-valid result echoing task + businessKey")
    void successEmission(@TempDir Path runDir) throws Exception {
        Map<String, String> bindings = stageRequest(runDir);

        RunManifest manifest = new RunManifest();
        String status = new StubAdapter(successBody())
                .captureToManifest(Map.of(), List.of(), bindings, null, manifest);

        assertEquals("SUCCESS", status);
        BankStatementUploadResult result = validateEmitted(runDir);
        assertEquals(BUSINESS_KEY, result.envelope().businessKey());
        assertEquals(BUSINESS_KEY, manifest.businessKey);
        assertEquals("!0X0!!", result.task().xeroShortCode());     // task echoed verbatim
    }

    @Test
    @DisplayName("FAILED: importedLineCount=0 + errorCategory still validates")
    void failedEmission(@TempDir Path runDir) throws Exception {
        Map<String, String> bindings = stageRequest(runDir);

        String status = new StubAdapter(failedBody())
                .captureToManifest(Map.of(), List.of(), bindings, null, new RunManifest());

        assertEquals("FAILED", status);
        validateEmitted(runDir);
    }

    // --- helpers ------------------------------------------------------------

    private static Map<String, String> stageRequest(Path runDir) {
        BankStatementUploadRequest request = new BankStatementUploadRequest(
                new EnvelopeMeta(UUID.randomUUID().toString(), BUSINESS_KEY, 1L, "es",
                        Instant.now(), "praxis-bpm/bank-statement-process", "proc-1"),
                task(), null, null,
                new Audit(null, null, null, SHA64));
        Path reqFile = runDir.resolve("bank-statement-upload-request.v1.json");
        EnvelopeIo.write(request, reqFile);

        Map<String, String> bindings = new HashMap<>();
        bindings.put("runtime.runDir", runDir.toString());
        bindings.put("runtime.runId", "20260618T000000-abcde");
        bindings.put(AbstractBankStatementAdapter.REQUEST_BINDING, reqFile.toString());
        bindings.put("params.businessKey", BUSINESS_KEY);
        return bindings;
    }

    private static BankStatementUploadResult validateEmitted(Path runDir) throws Exception {
        Path resultFile = runDir.resolve("bank-statement-upload-result.v1.json");
        assertTrue(Files.exists(resultFile), "result envelope not written");
        SchemaValidator.validate(Files.readAllBytes(resultFile),
                SchemaValidator.BANK_STATEMENT_UPLOAD_RESULT);
        return EnvelopeIo.read(resultFile, BankStatementUploadResult.class);
    }

    private static BankStatementTask task() {
        return new BankStatementTask(
                BankStatementTask.TYPE, BankStatementTask.OPERATION, TargetSystem.XERO,
                "e1f2a3b4-c5d6-4789-9abc-def012345678", "Neoproc Sociedad Anonima", "!0X0!!",
                "NEOPROCSOCIEDADANONIMA", "Banco Nacional", "920336542",
                "CR05015202001026284066", "USD",
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-17"),
                "NEOPROCSOCIEDADANONIMA_920336542_USD_2026060120260617.csv",
                2, "1279.50", "11211.00", "12490.50");
    }

    private static ResultBody successBody() {
        return new ResultBody(
                BankStatementStatus.SUCCESS, null, null, false, null,
                "Neoproc Sociedad Anonima", "920336542 (USD)",
                new Reconciliation("USD", 2, 2, "1279.50", "1279.50",
                        "11211.00", "11211.00", "12490.50", "12490.50", null),
                List.of(Check.passed(CheckName.ROW_COUNT, "2", "2")),
                List.of(), List.of(), null);
    }

    private static ResultBody failedBody() {
        return new ResultBody(
                BankStatementStatus.FAILED, ErrorCategory.ORG_NOT_FOUND,
                "Org !0X0!! not accessible.", false, FailedStage.ORG_SELECT, null, null,
                new Reconciliation("USD", 2, 0, "1279.50", null,
                        "11211.00", null, "12490.50", null, null),
                List.of(Check.failed(CheckName.ORG_SELECTED, "!0X0!!", "not found", "switch failed")),
                List.of(),
                List.of(new ErrorItem(ErrorCategory.ORG_NOT_FOUND, "Org not found", ErrorSeverity.ERROR)),
                null);
    }

    /** Stub returning a fixed body — stands in for the real Xero UI work. */
    private static final class StubAdapter extends AbstractBankStatementAdapter {
        private final ResultBody body;

        StubAdapter(ResultBody body) {
            this.body = body;
        }

        @Override
        protected UploadOutcome buildUploadOutcome(Map<String, String> bindings,
                                                   PortalCredentials credentials,
                                                   RunManifest manifest) {
            return new UploadOutcome(body.status(), body, "agent-worker/xero");
        }
    }
}
