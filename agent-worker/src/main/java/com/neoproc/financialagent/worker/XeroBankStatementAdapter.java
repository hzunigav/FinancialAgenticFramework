package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.contract.bankstatement.BankStatementStatus;
import com.neoproc.financialagent.contract.bankstatement.BankStatementTask;
import com.neoproc.financialagent.contract.bankstatement.BankStatementUploadRequest;
import com.neoproc.financialagent.contract.bankstatement.ErrorCategory;
import com.neoproc.financialagent.contract.bankstatement.ErrorItem;
import com.neoproc.financialagent.contract.bankstatement.ErrorSeverity;
import com.neoproc.financialagent.contract.bankstatement.FailedStage;
import com.neoproc.financialagent.contract.bankstatement.Reconciliation;
import com.neoproc.financialagent.contract.bankstatement.ResultBody;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import com.neoproc.financialagent.worker.portal.PortalDescriptor;
import com.microsoft.playwright.Page;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Xero bank-statement upload adapter.
 *
 * <p><b>SKELETON (Phase 2c).</b> The result-envelope emission is wired (via
 * {@link AbstractBankStatementAdapter}), but the actual Xero UI automation —
 * switch org by {@code task.xeroShortCode}, select the account by
 * {@code bankAccountNumber}, drive the Upload &rarr; Import settings &rarr;
 * Review wizard with the CSV at {@code params.source.csvPath}, handle the
 * duplicate warning, read back {@code importedLineCount} + balances, run the
 * checks — is <b>Phase 2d</b> and pending a login-DOM spike + live validation
 * against the demo org.
 *
 * <p>Until then {@link #beforeSteps} is a no-op and {@link #buildUploadOutcome}
 * returns a {@code FAILED}/{@code UNKNOWN} result (never a false SUCCESS), so
 * the emitted envelope is honest and schema-valid and the pipeline can be
 * exercised end-to-end.
 */
final class XeroBankStatementAdapter extends AbstractBankStatementAdapter {

    @Override
    public void beforeSteps(PortalDescriptor descriptor,
                            Page page,
                            Map<String, String> bindings,
                            Map<String, List<Map<String, String>>> listBindings,
                            PortalCredentials credentials,
                            RunManifest manifest) {
        // TODO(phase2d): the Xero UI flow. Selectors captured in Phase-0:
        //   org switch  : navigate go.xero.com/app/<task.xeroShortCode>/manage-bank-accounts
        //   file input  : input[data-automationid="select-file-control--input"]
        //   wizard next : [data-automationid="wizard-next-step-button"]
        manifest.step("xero", "beforeSteps skeleton — Xero UI automation pending (phase 2d)");
    }

    @Override
    protected UploadOutcome buildUploadOutcome(Map<String, String> bindings,
                                               PortalCredentials credentials,
                                               RunManifest manifest) {
        BankStatementTask task = EnvelopeIo.read(
                Path.of(require(bindings, REQUEST_BINDING)),
                BankStatementUploadRequest.class).task();

        // Nothing imported yet — reconciliation echoes the expected inputs with
        // importedLineCount=0 (schema requires currency + counts even on FAILED).
        Reconciliation reconciliation = new Reconciliation(
                task.currency(), task.expectedRowCount(), 0,
                task.expectedNetMovement(), null,
                task.expectedOpeningBalance(), null,
                task.expectedClosingBalance(), null, null);

        ResultBody body = new ResultBody(
                BankStatementStatus.FAILED,
                ErrorCategory.UNKNOWN,
                "Xero upload adapter not yet implemented (phase 2d).",
                false,
                FailedStage.UPLOAD,
                null, null,
                reconciliation,
                List.of(), List.of(),
                List.of(new ErrorItem(ErrorCategory.UNKNOWN,
                        "Xero UI automation pending (phase 2d).", ErrorSeverity.ERROR)),
                null);

        return new UploadOutcome(BankStatementStatus.FAILED, body, "agent-worker/xero");
    }
}
