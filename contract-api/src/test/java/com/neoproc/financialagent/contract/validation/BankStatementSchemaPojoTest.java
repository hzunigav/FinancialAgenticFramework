package com.neoproc.financialagent.contract.validation;

import com.neoproc.financialagent.contract.bankstatement.BankStatementStatus;
import com.neoproc.financialagent.contract.bankstatement.BankStatementTask;
import com.neoproc.financialagent.contract.bankstatement.BankStatementUploadRequest;
import com.neoproc.financialagent.contract.bankstatement.BankStatementUploadResult;
import com.neoproc.financialagent.contract.bankstatement.Check;
import com.neoproc.financialagent.contract.bankstatement.CheckName;
import com.neoproc.financialagent.contract.bankstatement.Diagnostics;
import com.neoproc.financialagent.contract.bankstatement.ErrorCategory;
import com.neoproc.financialagent.contract.bankstatement.ErrorItem;
import com.neoproc.financialagent.contract.bankstatement.ErrorSeverity;
import com.neoproc.financialagent.contract.bankstatement.FailedStage;
import com.neoproc.financialagent.contract.bankstatement.FileBody;
import com.neoproc.financialagent.contract.bankstatement.Reconciliation;
import com.neoproc.financialagent.contract.bankstatement.RejectedRow;
import com.neoproc.financialagent.contract.bankstatement.ResultBody;
import com.neoproc.financialagent.contract.bankstatement.TargetSystem;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Producer-side validation for the bank-statement records — the path the
 * agent-worker actually exercises ({@code validate(Object, schema)}), which
 * re-serializes through Jackson and so catches mistakes the byte-array
 * fixtures cannot: {@code java.time.Instant}/{@code LocalDate} formatting,
 * enum-name wire values, decimal-string money fields, and the
 * SUCCESS &harr; errorCategory if/then/else invariant from both directions.
 *
 * <p>The reused {@link Audit} record must populate ONLY {@code payloadSha256}
 * for bank statements — the schema's AuditBundle is {@code additionalProperties:false}
 * and forbids the payroll-side manifest/har/screenshot fields.
 */
class BankStatementSchemaPojoTest {

    private static final String SHA64 =
            "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";

    @Test
    @DisplayName("Cleartext request POJO with inline FileBody, Instant + LocalDate validates")
    void requestPojo_cleartextInline_validates() {
        BankStatementUploadRequest request = new BankStatementUploadRequest(
                envMeta(),
                task(),
                null,
                new FileBody(FileBody.FileRef.inline(
                        "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                        "RGF0ZSxBbW91bnQ=")),
                audit());

        SchemaValidator.validate(request, SchemaValidator.BANK_STATEMENT_UPLOAD_REQUEST);
    }

    @Test
    @DisplayName("SUCCESS result POJO (errorCategory null) validates")
    void resultPojo_success_validates() {
        ResultBody body = new ResultBody(
                BankStatementStatus.SUCCESS,
                null, null, false, null,
                "Neoproc Sociedad Anonima", "920336542 (USD)",
                new Reconciliation("USD", 2, 2,
                        "1279.50", "1279.50",
                        "11211.00", "11211.00",
                        "12490.50", "12490.50",
                        new Reconciliation.DateRange(
                                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-17"))),
                List.of(
                        Check.passed(CheckName.ORG_SELECTED, "e1f2a3b4", "e1f2a3b4"),
                        Check.passed(CheckName.ROW_COUNT, "2", "2"),
                        Check.passed(CheckName.CLOSING_BALANCE, "12490.50", "12490.50")),
                List.of(), List.of(),
                new Diagnostics("worker-run-44ab12", 1, 16240,
                        "https://go.xero.com/Bank/BankAccount.aspx?accountID=920336542",
                        "Statement imported. 2 of 2 lines.",
                        List.of("bankstmt/firm-1/920336542/2026-06-17/confirm.png"),
                        "cloudwatch:/financeagent/xero/worker-run-44ab12"));

        SchemaValidator.validate(result(body), SchemaValidator.BANK_STATEMENT_UPLOAD_RESULT);
    }

    @Test
    @DisplayName("FAILED result POJO with importedLineCount=0 and reconciliation present validates")
    void resultPojo_failedMinimalReconciliation_validates() {
        ResultBody body = new ResultBody(
                BankStatementStatus.FAILED,
                ErrorCategory.ORG_NOT_FOUND,
                "Xero org e1f2a3b4 not accessible to the shared login.",
                false,
                FailedStage.ORG_SELECT,
                null, null,
                new Reconciliation("USD", 2, 0,
                        "1279.50", null, "11211.00", null, "12490.50", null, null),
                List.of(Check.failed(CheckName.ORG_SELECTED, "e1f2a3b4", "not found", "org switch failed")),
                List.of(),
                List.of(new ErrorItem(ErrorCategory.ORG_NOT_FOUND, "Org not found", ErrorSeverity.ERROR)),
                null);

        SchemaValidator.validate(result(body), SchemaValidator.BANK_STATEMENT_UPLOAD_RESULT);
    }

    @Test
    @DisplayName("MISMATCH result POJO with rejectedRows + multiple errors validates")
    void resultPojo_mismatchFull_validates() {
        ResultBody body = new ResultBody(
                BankStatementStatus.MISMATCH,
                ErrorCategory.CLOSING_BALANCE_MISMATCH,
                "Imported 2 lines but the closing balance is off by 230.50.",
                false,
                FailedStage.VERIFY,
                "Neoproc Sociedad Anonima", "920336542 (USD)",
                new Reconciliation("USD", 2, 2,
                        "1279.50", "1049.00",
                        "11211.00", "11211.00",
                        "12490.50", "12260.00",
                        new Reconciliation.DateRange(
                                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-17"))),
                List.of(
                        Check.passed(CheckName.ROW_COUNT, "2", "2"),
                        Check.failed(CheckName.NET_MOVEMENT, "1279.50", "1049.00", "off by 230.50"),
                        Check.failed(CheckName.CLOSING_BALANCE, "12490.50", "12260.00", "off by 230.50")),
                List.of(new RejectedRow(2, "2026-06-12,-230.50,Bank Fee,Wire fee,FEE-0612",
                        "Xero flagged as possible duplicate")),
                List.of(
                        new ErrorItem(ErrorCategory.CLOSING_BALANCE_MISMATCH, "Closing balance off by 230.50", ErrorSeverity.ERROR),
                        new ErrorItem(ErrorCategory.NET_MOVEMENT_MISMATCH, "Net movement off by 230.50", ErrorSeverity.ERROR)),
                new Diagnostics("worker-run-44ab12", 1, 18432, null, null, List.of(), null));

        SchemaValidator.validate(result(body), SchemaValidator.BANK_STATEMENT_UPLOAD_RESULT);
    }

    @Test
    @DisplayName("SUCCESS with a non-null errorCategory is rejected (if/then/else invariant)")
    void resultPojo_successWithErrorCategory_failsValidation() {
        ResultBody body = new ResultBody(
                BankStatementStatus.SUCCESS,
                ErrorCategory.CLOSING_BALANCE_MISMATCH,   // <-- illegal on SUCCESS
                null, false, null, null, null,
                new Reconciliation("USD", 2, 2, null, null, null, null, null, null, null),
                List.of(), List.of(), List.of(), null);

        assertThrows(SchemaValidationException.class,
                () -> SchemaValidator.validate(result(body), SchemaValidator.BANK_STATEMENT_UPLOAD_RESULT));
    }

    @Test
    @DisplayName("Non-SUCCESS status without an errorCategory is rejected (if/then/else invariant)")
    void resultPojo_mismatchWithoutErrorCategory_failsValidation() {
        ResultBody body = new ResultBody(
                BankStatementStatus.MISMATCH,
                null,                                     // <-- required when status != SUCCESS
                "missing category", false, FailedStage.VERIFY, null, null,
                new Reconciliation("USD", 2, 2, null, null, null, null, null, null, null),
                List.of(), List.of(), List.of(), null);

        assertThrows(SchemaValidationException.class,
                () -> SchemaValidator.validate(result(body), SchemaValidator.BANK_STATEMENT_UPLOAD_RESULT));
    }

    // --- helpers ------------------------------------------------------------

    private static BankStatementTask task() {
        return new BankStatementTask(
                BankStatementTask.TYPE, BankStatementTask.OPERATION, TargetSystem.XERO,
                "e1f2a3b4-c5d6-4789-9abc-def012345678", "Neoproc Sociedad Anonima",
                "NEOPROCSOCIEDADANONIMA", "Banco Nacional", "920336542",
                "CR05015202001026284066", "USD",
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-17"),
                "NEOPROCSOCIEDADANONIMA_920336542_USD_2026060120260617.csv",
                2, "1279.50", "11211.00", "12490.50");
    }

    private static EnvelopeMeta envMeta() {
        return new EnvelopeMeta(
                UUID.randomUUID().toString(),
                "NEOPROCSOCIEDADANONIMA::920336542::USD::20260601-20260617",
                1L, "es", Instant.now(), "financeagent-worker/xero", "worker-run-44ab12");
    }

    private static Audit audit() {
        // Bank-statement AuditBundle permits ONLY payloadSha256.
        return new Audit(null, null, null, SHA64);
    }

    private static BankStatementUploadResult result(ResultBody body) {
        return new BankStatementUploadResult(envMeta(), task(), null, body, audit());
    }
}
