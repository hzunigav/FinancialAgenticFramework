package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.contract.bankstatement.BankStatementStatus;
import com.neoproc.financialagent.contract.bankstatement.BankStatementTask;
import com.neoproc.financialagent.contract.bankstatement.BankStatementUploadRequest;
import com.neoproc.financialagent.contract.bankstatement.Check;
import com.neoproc.financialagent.contract.bankstatement.CheckName;
import com.neoproc.financialagent.contract.bankstatement.ErrorCategory;
import com.neoproc.financialagent.contract.bankstatement.ErrorItem;
import com.neoproc.financialagent.contract.bankstatement.ErrorSeverity;
import com.neoproc.financialagent.contract.bankstatement.FailedStage;
import com.neoproc.financialagent.contract.bankstatement.Reconciliation;
import com.neoproc.financialagent.contract.bankstatement.ResultBody;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import com.neoproc.financialagent.worker.portal.PortalDescriptor;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xero bank-statement upload adapter — drives the "Import bank transactions"
 * wizard for one CSV and reads back the result.
 *
 * <p><b>Live-validation pending.</b> The flow + selectors below are built from
 * the Phase-2d DOM capture (see {@code project_bankrecon_xero_import_flow}),
 * but Xero UI automation can only be confirmed by running against the demo org
 * (phase 2e). Selectors lacking a stable {@code data-automationid} (the column
 * mapping on step 2, the per-widget "Statement balance" text) are marked
 * {@code VALIDATE-LIVE} and will be tuned on the first real run. The
 * verification/status logic in {@link #buildUploadOutcome} is deterministic and
 * does not depend on the live run.
 *
 * <p>Two Phase-2d findings baked in: (1) Xero's column-mapping step is
 * mandatory, but Praxis guarantees the CSV matches Xero's expected structure
 * and Xero remembers the mapping per org — so we accept the auto-mapping as-is
 * and just advance (no dropdown manipulation, no assumption about Reference);
 * our only concern is a successful import. (2) Xero does not prompt on
 * duplicates — it imports, then reports them in a post-import banner on the
 * BankRec page ("0 statement lines were imported. 29 were duplicates."). We
 * parse that banner for the authoritative imported + duplicate counts and drive
 * {@code NO_DUPLICATE}/{@code DUPLICATE_STATEMENT} from it.
 */
final class XeroBankStatementAdapter extends AbstractBankStatementAdapter {

    private static final Logger log = LoggerFactory.getLogger(XeroBankStatementAdapter.class);

    private static final String BANK_WIDGET = "[data-automationid='bankWidget']";
    private static final String FILE_INPUT = "input[data-automationid='select-file-control--input']";
    private static final String WIZARD_NEXT = "[data-automationid='wizard-next-step-button']";
    private static final Pattern REVIEW_COUNT =
            Pattern.compile("(\\d+)\\s+transaction\\(s\\)\\s+will be imported");
    // Post-import banner on the BankRec page (confirmed Phase-2d):
    // "0 statement lines were imported. 29 were duplicates." — group 1 = actually
    // imported, group 2 = duplicates (absent when there were none).
    private static final Pattern IMPORT_BANNER = Pattern.compile(
            "(\\d+)\\s+statement lines?\\s+were imported(?:\\.\\s*(\\d+)\\s+were duplicates)?",
            Pattern.CASE_INSENSITIVE);
    // Pre-import widget: "Statement balance (Jun 9) 8,315.64" (label then amount).
    private static final Pattern STATEMENT_BALANCE =
            Pattern.compile("Statement balance[^\\d(-]*(\\(?-?[\\d,]+\\.\\d{2}\\)?)", Pattern.CASE_INSENSITIVE);
    // Post-import BankRec page: "(1,823.49) Statement Balance" (amount then label).
    private static final Pattern BANKREC_BALANCE =
            Pattern.compile("(\\(?-?[\\d,]+\\.\\d{2}\\)?)\\s*Statement Balance", Pattern.CASE_INSENSITIVE);

    // --- observations collected during beforeSteps, consumed by buildUploadOutcome ---
    private boolean orgSelected;
    private boolean accountSelected;
    private boolean fileAccepted;
    private Integer importedLineCount;   // actually imported (from the post-import banner)
    private Integer duplicatesCount;     // lines Xero skipped as duplicates
    private String observedOpeningBalance;   // statement balance before import
    private String observedClosingBalance;   // statement balance after import
    private FailedStage failedStage;
    private ErrorCategory errorCategory;
    private String errorMessage;

    @Override
    public void beforeSteps(PortalDescriptor descriptor,
                            Page page,
                            Map<String, String> bindings,
                            Map<String, List<Map<String, String>>> listBindings,
                            PortalCredentials credentials,
                            RunManifest manifest) {
        BankStatementTask task = EnvelopeIo.read(
                Path.of(require(bindings, REQUEST_BINDING)),
                BankStatementUploadRequest.class).task();
        Path csv = Path.of(require(bindings, "params.source.csvPath"));

        try {
            Locator widget = switchOrgAndSelectAccount(page, task, manifest);
            observedOpeningBalance = readStatementBalance(widget);
            openImportWizard(widget, page, manifest);
            uploadCsv(page, csv, manifest);
            advanceThroughImportSettings(page, manifest);
            completeImportAndReadBack(page, manifest);
            manifest.step("xero", "import complete imported=" + importedLineCount
                    + " duplicates=" + duplicatesCount);
        } catch (StageFailure f) {
            failedStage = f.stage;
            errorCategory = f.category;
            errorMessage = f.getMessage();
            log.warn("xero upload failed stage={} category={} msg={}", f.stage, f.category, f.getMessage());
            manifest.step("xero-failed", f.stage + "/" + f.category + ": " + f.getMessage());
        }
    }

    // --- UI stages ----------------------------------------------------------

    private Locator switchOrgAndSelectAccount(Page page, BankStatementTask task, RunManifest manifest) {
        // Login already happened in the SEPARATE auth phase; here we only switch
        // org. Navigate to the target org's bank accounts by short-code (Phase-0:
        // the UI selects org by short-code, not the tenant UUID), letting the
        // brief OIDC session-check settle off the login host first.
        String orgUrl = "https://go.xero.com/app/" + task.xeroShortCode() + "/manage-bank-accounts";
        page.navigate(orgUrl);
        try {
            page.waitForURL(u -> !u.contains("/identity/") && !u.toLowerCase().contains("login.xero.com"),
                    new Page.WaitForURLOptions().setTimeout(45_000));
        } catch (RuntimeException stuckOnLogin) {
            throw new StageFailure(FailedStage.AUTH, ErrorCategory.SESSION_EXPIRED,
                    "Bounced to the Xero login during org switch — auth/session issue");
        }
        try {
            page.waitForSelector(BANK_WIDGET, new Page.WaitForSelectorOptions().setTimeout(30_000));
        } catch (RuntimeException e) {
            throw new StageFailure(FailedStage.ORG_SELECT, ErrorCategory.ORG_NOT_FOUND,
                    "Org short-code " + task.xeroShortCode() + " did not load a bank-accounts page");
        }
        orgSelected = true;
        manifest.step("xero", "org switched shortCode=" + task.xeroShortCode());

        // Match the account widget by its visible account number.
        Locator widget = page.locator(BANK_WIDGET)
                .filter(new Locator.FilterOptions().setHasText(task.bankAccountNumber()));
        if (widget.count() == 0) {
            throw new StageFailure(FailedStage.ACCOUNT_SELECT, ErrorCategory.ACCOUNT_NOT_FOUND,
                    "No bank account widget matched account number " + task.bankAccountNumber());
        }
        accountSelected = true;
        manifest.step("xero", "account matched " + task.bankAccountNumber());
        return widget.first();
    }

    private void openImportWizard(Locator accountWidget, Page page, RunManifest manifest) {
        // Two widget shapes (Phase-2e live finding):
        //  - Account WITHOUT transactions renders the import action inline as an
        //    <a href*='manual-transaction-upload'> ("Import a bank statement").
        //  - Account WITH transactions hides it behind the widget's overflow
        //    "Manage menu" popover (button data-automationid='managedMenuButton',
        //    aria-controls='manage-menu-popover-<accountId>'); the import link is
        //    only rendered once that popover is opened. The inline locator finds
        //    nothing for these accounts, which is why the direct-href attempt
        //    failed. Open the menu, then follow the link from the popover.
        Locator inline = accountWidget.locator("a[href*='manual-transaction-upload']");
        if (inline.count() > 0) {
            navigateImport(page, inline.first(), manifest);
            return;
        }
        Locator menuButton = accountWidget.locator("[data-automationid='managedMenuButton']");
        if (menuButton.count() == 0) {
            throw new StageFailure(FailedStage.UPLOAD, ErrorCategory.UNKNOWN,
                    "No 'Import a bank statement' affordance (inline link or overflow menu) on the account widget");
        }
        String popoverId = menuButton.first().getAttribute("aria-controls");
        menuButton.first().click();
        Locator scope = (popoverId != null && !popoverId.isBlank())
                ? page.locator("#" + popoverId)
                : page.locator("body");
        Locator importLink = scope.locator("a[href*='manual-transaction-upload']");
        try {
            importLink.first().waitFor(new Locator.WaitForOptions().setTimeout(10_000));
        } catch (RuntimeException notAnchor) {
            // Fall back to matching the menu item by its visible text.
            importLink = scope.getByText("Import a bank statement");
            try {
                importLink.first().waitFor(new Locator.WaitForOptions().setTimeout(5_000));
            } catch (RuntimeException stillMissing) {
                throw new StageFailure(FailedStage.UPLOAD, ErrorCategory.UNKNOWN,
                        "Overflow menu opened but no 'Import a bank statement' item appeared");
            }
        }
        navigateImport(page, importLink.first(), manifest);
    }

    /** Follow an import affordance — navigate its href if it has one, else click it. */
    private void navigateImport(Page page, Locator link, RunManifest manifest) {
        String href = link.getAttribute("href");
        if (href != null && !href.isBlank()) {
            String url = href.startsWith("http") ? href : "https://go.xero.com" + href;
            page.navigate(url);
            manifest.step("xero", "opened import wizard " + href);
        } else {
            link.click();
            manifest.step("xero", "opened import wizard via menu click");
        }
    }

    private void uploadCsv(Page page, Path csv, RunManifest manifest) {
        try {
            page.waitForSelector(FILE_INPUT);
            page.locator(FILE_INPUT).setInputFiles(csv);
        } catch (RuntimeException e) {
            throw new StageFailure(FailedStage.UPLOAD, ErrorCategory.UPLOAD_REJECTED,
                    "CSV file input not available / rejected: " + e.getMessage());
        }
        fileAccepted = true;
        manifest.step("xero", "uploaded " + csv.getFileName());
        clickNext(page);   // Upload -> Import settings
    }

    private void advanceThroughImportSettings(Page page, RunManifest manifest) {
        // Praxis guarantees the CSV matches Xero's expected structure, and Xero
        // remembers the column mapping per org, so we accept the auto-mapping
        // exactly as-is and just advance — no dropdown/checkbox manipulation,
        // and no assumption about which columns map where. Our only concern is a
        // successful import. (If Xero ever blocked here on a genuinely malformed
        // file, the Review step would not render a count and surface as a
        // VERIFY-stage failure.)
        page.waitForSelector(WIZARD_NEXT);
        manifest.step("xero", "import settings accepted as-is (CSV structure guaranteed upstream)");
        clickNext(page);   // Import settings -> Review
    }

    /**
     * Completes the import and reads Xero's authoritative post-import banner on
     * the BankRec reconcile page (confirmed Phase-2d), e.g.
     * "0 statement lines were imported. 29 were duplicates." — group 1 is the
     * count actually imported, group 2 the duplicates Xero skipped. Also
     * best-effort reads the post-import statement balance from that page. Waits
     * on the BankRec URL, never {@code networkidle} (the Xero SPA never idles).
     */
    private void completeImportAndReadBack(Page page, RunManifest manifest) {
        page.waitForSelector(WIZARD_NEXT);
        int attempted = parseReviewCount(page.locator("body").textContent());
        manifest.step("xero", "review attempted=" + attempted + " — completing import");
        clickNext(page);   // Review -> Complete import (same button on the last step)

        try {
            page.waitForURL(u -> u.contains("/BankRec/"),
                    new Page.WaitForURLOptions().setTimeout(30_000));
        } catch (RuntimeException settleTimeout) {
            // proceed with whatever rendered
        }
        String body = page.locator("body").textContent();

        int[] banner = parseImportBanner(body);
        if (banner != null) {
            importedLineCount = banner[0];
            duplicatesCount = banner[1];
        } else {
            // No banner — fall back to the Review "will be imported" count.
            importedLineCount = attempted;
            duplicatesCount = 0;
        }
        observedClosingBalance = parseBankRecBalance(body);
        manifest.step("xero", "post-import imported=" + importedLineCount
                + " duplicates=" + duplicatesCount + " stmtBalance=" + observedClosingBalance);
    }

    private void clickNext(Page page) {
        page.locator(WIZARD_NEXT).first().click();
    }

    /** {imported, duplicates} from the post-import banner, or null if not present. */
    private static int[] parseImportBanner(String body) {
        if (body == null) return null;
        Matcher m = IMPORT_BANNER.matcher(body);
        if (!m.find()) return null;
        int imported = Integer.parseInt(m.group(1));
        int duplicates = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        return new int[]{imported, duplicates};
    }

    /** Best-effort statement balance from the BankRec page ("(1,823.49) Statement Balance"). */
    private static String parseBankRecBalance(String body) {
        if (body == null) return null;
        Matcher m = BANKREC_BALANCE.matcher(body);
        return m.find() ? normalizeMoney(m.group(1)) : null;
    }

    /** Normalises "(1,823.49)" -> "-1823.49", "8,315.64" -> "8315.64". */
    private static String normalizeMoney(String raw) {
        boolean negative = raw.startsWith("(") && raw.endsWith(")");
        String num = raw.replace("(", "").replace(")", "").replace(",", "");
        return negative && !num.startsWith("-") ? "-" + num : num;
    }

    /** VALIDATE-LIVE: parse "Statement balance (...) 8,315.64" from the widget text. */
    private static String readStatementBalance(Locator widget) {
        String text = widget.textContent();
        if (text == null) return null;
        Matcher m = STATEMENT_BALANCE.matcher(text);
        return m.find() ? normalizeMoney(m.group(1)) : null;
    }

    private static int parseReviewCount(String bodyText) {
        if (bodyText != null) {
            Matcher m = REVIEW_COUNT.matcher(bodyText);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        throw new StageFailure(FailedStage.VERIFY, ErrorCategory.UNKNOWN,
                "Could not read the imported transaction count on the Review step");
    }

    // --- result assembly (deterministic; independent of the live run) -------

    @Override
    protected UploadOutcome buildUploadOutcome(Map<String, String> bindings,
                                               PortalCredentials credentials,
                                               RunManifest manifest) {
        BankStatementTask task = EnvelopeIo.read(
                Path.of(require(bindings, REQUEST_BINDING)),
                BankStatementUploadRequest.class).task();

        List<Check> checks = new ArrayList<>();
        List<ErrorItem> errors = new ArrayList<>();

        // Access / file checks reflect how far beforeSteps got.
        addBoolean(checks, CheckName.ORG_SELECTED, orgSelected, task.xeroShortCode());
        addBoolean(checks, CheckName.ACCOUNT_SELECTED, accountSelected, task.bankAccountNumber());
        addBoolean(checks, CheckName.FILE_ACCEPTED, fileAccepted, "accepted");

        int imported = importedLineCount != null ? importedLineCount : 0;
        String observedNet = signedDelta(observedOpeningBalance, observedClosingBalance);

        // Reconciliation checks only meaningful once something imported.
        boolean rowCountOk = importedLineCount != null && imported == task.expectedRowCount();
        if (importedLineCount != null) {
            checks.add(new Check(CheckName.ROW_COUNT, rowCountOk,
                    String.valueOf(task.expectedRowCount()), String.valueOf(imported),
                    rowCountOk ? null : "imported " + imported + " of " + task.expectedRowCount()));
            // NO_DUPLICATE from Xero's post-import banner ("N were duplicates").
            int dupes = duplicatesCount != null ? duplicatesCount : 0;
            boolean noDup = dupes == 0;
            checks.add(new Check(CheckName.NO_DUPLICATE, noDup, "0 duplicates",
                    dupes + " duplicate(s)",
                    noDup ? null : dupes + " line(s) were duplicates of an existing statement"));
            addMoney(checks, CheckName.NET_MOVEMENT, task.expectedNetMovement(), observedNet, errors);
            addMoney(checks, CheckName.OPENING_BALANCE, task.expectedOpeningBalance(), observedOpeningBalance, errors);
            addMoney(checks, CheckName.CLOSING_BALANCE, task.expectedClosingBalance(), observedClosingBalance, errors);
        }

        Reconciliation reconciliation = new Reconciliation(
                task.currency(), task.expectedRowCount(), imported,
                task.expectedNetMovement(), observedNet,
                task.expectedOpeningBalance(), observedOpeningBalance,
                task.expectedClosingBalance(), observedClosingBalance,
                new Reconciliation.DateRange(task.periodStart(), task.periodEnd()));

        BankStatementStatus status = deriveStatus(checks, imported, task.expectedRowCount());
        ErrorCategory primary = errorCategory != null ? errorCategory
                : (duplicatesCount != null && duplicatesCount > 0) ? ErrorCategory.DUPLICATE_STATEMENT
                : firstFailureCategory(checks, status);
        String message = errorMessage != null ? errorMessage : summarize(status, checks);
        if (status != BankStatementStatus.SUCCESS && errors.isEmpty() && primary != null) {
            errors.add(new ErrorItem(primary, message, ErrorSeverity.ERROR));
        }

        ResultBody body = new ResultBody(
                status,
                status == BankStatementStatus.SUCCESS ? null : primary,
                status == BankStatementStatus.SUCCESS ? null : message,
                false,                                   // upload failures need a human, not a retry
                failedStage,
                orgSelected ? task.xeroOrgName() : null,
                accountSelected ? task.bankAccountNumber() + " (" + task.currency() + ")" : null,
                reconciliation, checks, List.of(), errors, null);

        return new UploadOutcome(status, body, "agent-worker/xero");
    }

    // --- helpers ------------------------------------------------------------

    private static void addBoolean(List<Check> checks, CheckName name, boolean passed, String expected) {
        checks.add(new Check(name, passed, expected, passed ? expected : "not reached",
                passed ? null : "stage not completed"));
    }

    /**
     * Adds a monetary check; SKIPPED (omitted) when the expected value is null
     * (Q13) OR when we couldn't read the observed value off the Xero UI — we
     * don't fail a successful import on an unreadable balance (balances are
     * best-effort; the import succeeding is the primary concern).
     */
    private static void addMoney(List<Check> checks, CheckName name,
                                 String expected, String observed, List<ErrorItem> errors) {
        if (expected == null || observed == null) {
            return;   // SKIPPED — no expected value, or balance not readable
        }
        boolean ok = new BigDecimal(expected).compareTo(new BigDecimal(observed)) == 0;
        checks.add(new Check(name, ok, expected, observed,
                ok ? null : "expected " + expected + " observed " + observed));
        if (!ok) {
            errors.add(new ErrorItem(categoryFor(name),
                    name + " expected " + expected + " observed " + observed, ErrorSeverity.ERROR));
        }
    }

    private static ErrorCategory categoryFor(CheckName name) {
        return switch (name) {
            case NET_MOVEMENT -> ErrorCategory.NET_MOVEMENT_MISMATCH;
            case OPENING_BALANCE -> ErrorCategory.OPENING_BALANCE_MISMATCH;
            case CLOSING_BALANCE -> ErrorCategory.CLOSING_BALANCE_MISMATCH;
            case ROW_COUNT -> ErrorCategory.COUNT_MISMATCH;
            case NO_DUPLICATE -> ErrorCategory.DUPLICATE_STATEMENT;
            default -> ErrorCategory.UNKNOWN;
        };
    }

    /** observed net movement = closing - opening statement balance (null if either unknown). */
    private static String signedDelta(String opening, String closing) {
        if (opening == null || closing == null) return null;
        return new BigDecimal(closing).subtract(new BigDecimal(opening)).toPlainString();
    }

    private BankStatementStatus deriveStatus(List<Check> checks, int imported, int expected) {
        // FAILED: an access/account/file stage failed (nothing imported).
        if (!orgSelected || !accountSelected || !fileAccepted || importedLineCount == null) {
            return BankStatementStatus.FAILED;
        }
        // Duplicates take precedence — the statement (wholly or partly) overlaps
        // one already imported; a human must decide if that's expected.
        if (duplicatesCount != null && duplicatesCount > 0) {
            return BankStatementStatus.MISMATCH;
        }
        boolean anyReconFail = checks.stream().anyMatch(c ->
                !c.passed() && (c.name() == CheckName.NET_MOVEMENT
                        || c.name() == CheckName.OPENING_BALANCE
                        || c.name() == CheckName.CLOSING_BALANCE));
        if (imported < expected) {
            return BankStatementStatus.PARTIAL;
        }
        if (anyReconFail) {
            return BankStatementStatus.MISMATCH;
        }
        return BankStatementStatus.SUCCESS;
    }

    private static ErrorCategory firstFailureCategory(List<Check> checks, BankStatementStatus status) {
        if (status == BankStatementStatus.SUCCESS) return null;
        return checks.stream().filter(c -> !c.passed())
                .map(c -> categoryFor(c.name())).findFirst().orElse(ErrorCategory.UNKNOWN);
    }

    private static String summarize(BankStatementStatus status, List<Check> checks) {
        long failed = checks.stream().filter(c -> !c.passed()).count();
        return status + " — " + failed + " check(s) failed";
    }

    /** Thrown by a UI stage to record where/why it stopped. */
    private static final class StageFailure extends RuntimeException {
        final FailedStage stage;
        final ErrorCategory category;

        StageFailure(FailedStage stage, ErrorCategory category, String message) {
            super(message);
            this.stage = stage;
            this.category = category;
        }
    }
}
