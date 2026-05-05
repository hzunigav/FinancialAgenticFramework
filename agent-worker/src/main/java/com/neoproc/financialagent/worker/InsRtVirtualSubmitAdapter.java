package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.CredentialsProvider;
import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.common.crypto.EnvelopeCipher;
import com.neoproc.financialagent.common.match.EmployeeMatcher;
import com.neoproc.financialagent.contract.payroll.EnvelopeStatus;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitRequest;
import com.neoproc.financialagent.contract.payroll.RosterDiff;
import com.neoproc.financialagent.contract.payroll.SubmitRequestBody;
import com.neoproc.financialagent.contract.payroll.SubmitResultBody;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import com.neoproc.financialagent.worker.portal.PortalDescriptor;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Submit adapter for INS RT-Virtual — the shared-creds salary save flow.
 *
 * <p>Flow:
 * <ol>
 *   <li>Land on /WorkSpace dashboard (multiple póliza cards visible
 *       under the shared-creds account).</li>
 *   <li>Pick the póliza card whose Identificación matches
 *       {@code params.clientIdentifier} (cédula jurídica). Today the
 *       firm:póliza relation is 1:1, so a single card per cédula is
 *       expected; multiple matches surface a hard failure rather than
 *       guess. The day a firm gets a second póliza, the listener will
 *       need a {@code params.policyNumber} tiebreaker.</li>
 *   <li>Dismiss the welcome dialog → expand the Planilla menu →
 *       Preparar planilla → Nueva planilla.</li>
 *   <li>The Nueva planilla dialog lists prior-period planillas to clone.
 *       Operator confirmed the most recent prior planilla is the
 *       source-template; the adapter checks the first row's checkbox
 *       and clicks Cargar lista seleccionada → Aceptar.</li>
 *   <li>On the edit page, scrape every row (cédula + name + current
 *       salary) across all pages, then per canonical employee match by
 *       cédula via {@link EmployeeMatcher#matchWithDrift}, fill the
 *       row's {@code input[name="salario"]} with the canonical salary,
 *       and Tab to commit. INS auto-saves on blur — visible toast says
 *       "Auto guardado: ..." — so no per-row Aplicar click is needed.</li>
 *   <li>Click Continuar; a Resumen dialog opens with the post-edit
 *       totals (Consecutivo temporal, Total de salarios, Trabajadores
 *       reportados). Scrape, then click Cancelar to leave the planilla
 *       in the editable-but-saved state. The HITL final-submit user
 *       task is the only authority allowed to click Presentar planilla;
 *       the adapter's {@link #FORBIDDEN_BUTTON_LABELS} enforces this.</li>
 * </ol>
 *
 * <p>Status routing follows the same rules as CCSS Sicere:
 * {@code SUCCESS} when totals match AND roster diff empty AND no
 * name-drift; {@code PARTIAL} when roster diff non-empty OR name-drift
 * present; {@code MISMATCH} when totals diverge but roster matches;
 * {@code FAILED} on hard errors.
 */
final class InsRtVirtualSubmitAdapter extends AbstractSubmitAdapter {

    private static final Logger log = LoggerFactory.getLogger(InsRtVirtualSubmitAdapter.class);

    // Post-login dashboard: each póliza is an idFooterCard_<polizaNumber>
    // wrapper. Cards expose Identificación (cédula jurídica), Número de
    // póliza, Tipo, Calendario, and a Ver póliza button.
    private static final String POLIZA_CARD_SELECTOR_PREFIX = "[id^=\"idFooterCard_\"]";

    // Resumen de planilla dialog (opens on Continuar). The labels are
    // stable Spanish strings; the values appear inside the same role=dialog
    // container, so a full innerText scrape + regex extract is more robust
    // than chasing PrimeReact / MUI sibling-axis quirks across releases.
    private static final String RESUMEN_DIALOG = "[role=\"dialog\"]:has-text(\"Resumen de planilla\")";

    // INS RT does not auto-commit the planilla — Cancelar exits the
    // Resumen dialog leaving the per-row auto-saved edits intact (the
    // planilla stays in en-edición state). Presentar planilla is the
    // formal commit and is owned by HITL; see FORBIDDEN_BUTTON_LABELS.
    //
    // The Resumen dialog renders both buttons with stable ids:
    //   #btn-cancel-payroll  → "Cancelar"           (this adapter clicks)
    //   #btn-submit-payroll  → "Presentar planilla" (HITL-only; FORBIDDEN)
    // Anchoring on the id avoids collision with cosmetic notification-bar
    // "Cerrar" buttons that share visible-text patterns. Verified via
    // diag.dumpResumen capture 2026-05-04T15:11.
    private static final String CANCELAR_BUTTON = "#btn-cancel-payroll";
    private static final String CONTINUAR_BUTTON = "button:visible:has-text(\"Continuar\")";

    // Pagination chrome. INS's paginator renders as:
    //   <button class="circle-button-left" [disabled]>   ← prev
    //   <span class="page-status">1 de 2</span>          ← X of N
    //   <button class="circle-button-right" [disabled]>  ← next
    // The page-status span is the authoritative signal for total pages
    // and for confirming that a Next click actually advanced; the
    // disabled attribute tells us when each end is reached.
    private static final String PAGE_NEXT = ".circle-button-right";
    private static final String PAGE_PREV = ".circle-button-left";
    private static final String PAGE_STATUS = ".page-status";
    // INS renders the current-page "X" in an editable <input> inside the
    // page-status span; only the literal "de N" portion is in innerText.
    // Make the leading X optional so the regex catches both forms.
    // We only consume the trailing N as totalPages — the current page
    // index is already captured per row when we iterate via gotoNextPage.
    private static final Pattern PAGE_STATUS_PATTERN =
            Pattern.compile("(?:(\\d+)\\s+)?de\\s+(\\d+)");

    /**
     * Buttons the adapter will refuse to click, ever — visible-text denylist
     * enforced by {@link #safeClick}. The adapter's happy path does not
     * select any of these by selector, but a future refactor that
     * accidentally pointed an existing {@code safeClick} at a Presentar
     * button would fail fast instead of submitting the planilla.
     *
     * <p>{@code Presentar planilla} formally files the planilla with INS.
     * Once clicked, the planilla is legally submitted and the worker
     * becomes responsible for that submission — outside the save-only
     * contract. The HITL final-submit user task owns this action; the
     * agent never does.
     */
    private static final List<String> FORBIDDEN_BUTTON_LABELS = List.of(
            "presentar planilla",
            "presentar",
            // Acciones rápidas dropdown sibling that files an EMPTY planilla
            // (zero trabajadores, zero salaries) — observed 2026-05-04 after
            // INS renamed the dropdown items. Worse than just "Presentar
            // planilla" because clicking it bypasses the Resumen dialog
            // entirely; the planilla is filed straight away. Hard-deny
            // by exact text.
            "presentar planilla sin trabajadores"
    );

    // Cédula pattern — Costa Rica national IDs are 9 digits, juridicas 10,
    // residency cards 11-12. Used to disambiguate the cédula cell from
    // other numeric cells (horas, salary) during row scrape when column
    // order isn't yet pinned by a fixture.
    private static final Pattern CEDULA_PATTERN = Pattern.compile("\\d{9,12}");

    // Captured during beforeSteps, consumed in buildSubmitOutcome.
    private BigDecimal canonicalGrandTotal = BigDecimal.ZERO;
    private BigDecimal portalReportedTotal = BigDecimal.ZERO;
    private RosterDiff rosterDiff = new RosterDiff(List.of(), List.of());
    private List<SubmitResultBody.SubmittedRow> submittedRows = List.of();
    private List<SubmitResultBody.Signal> nameDriftSignals = List.of();
    // Accumulator for canonicals whose saveRow threw mid-loop (search
    // returned 0 or > 1 rows, fill error, accordion timeout, etc.). The
    // run no longer aborts on the first failure — it tags the canonical
    // with a FILL_FAILED signal and continues with the rest, so HITL
    // sees the partial picture instead of nothing.
    private List<SubmitResultBody.Signal> fillFailureSignals = List.of();
    private String sourceCaptureEnvelopeId;
    private String clientIdentifier;
    private String consecutivoTemporal;
    private int trabajadoresReportados;
    // Verbatim innerText of the Resumen de planilla dialog. Surfaced as
    // PortalConfirmation.rawText so HITL has the exact portal-side
    // numbers (consecutivo / trabajadores / total / promedio) without
    // having to re-render an artifact. The schema requires this field
    // to be a non-null string — null was rejected on the result-publish
    // path on 2026-05-05 (SchemaValidationException at
    // $.result.portalConfirmation.rawText).
    private String resumenRawText = "";
    // Dry-run mode: navigates, scrapes, matches, but does NOT touch the
    // portal (no fill, no Continuar). Set with -Dparams.dryRun=true (CLI).
    private boolean dryRun;

    @Override
    public void beforeSteps(PortalDescriptor descriptor,
                            Page page,
                            Map<String, String> bindings,
                            Map<String, List<Map<String, String>>> listBindings,
                            PortalCredentials credentials,
                            RunManifest manifest) {
        try {
            beforeStepsImpl(descriptor, page, bindings, listBindings, credentials, manifest);
        } catch (RuntimeException e) {
            // PortalRunService only fires afterCapture on the success path —
            // when beforeSteps throws, the session is left logged in and the
            // shared-creds account accumulates "concurrent session detected"
            // banners on every subsequent run. Best-effort logout here so
            // failure cycles don't poison success cycles.
            performLogoutBestEffort(page, manifest, "logout-on-error");
            throw e;
        }
    }

    private void beforeStepsImpl(PortalDescriptor descriptor,
                                 Page page,
                                 Map<String, String> bindings,
                                 Map<String, List<Map<String, String>>> listBindings,
                                 PortalCredentials credentials,
                                 RunManifest manifest) {

        clientIdentifier = bindings.get("params.clientIdentifier");
        if (clientIdentifier == null || clientIdentifier.isBlank()) {
            throw new IllegalStateException(
                    "ins-rt-virtual is shared-creds; envelope must carry task.clientIdentifier "
                            + "(cédula jurídica, e.g. \"3101680139\") to select the póliza card");
        }

        dryRun = Boolean.parseBoolean(bindings.getOrDefault("params.dryRun", "false"));
        boolean diagDumpResumen = Boolean.parseBoolean(
                bindings.getOrDefault("params.diag.dumpResumen", "false"));
        log.info("ins-rt-virtual starting clientIdentifier={} dryRun={} diagDumpResumen={}",
                clientIdentifier, dryRun, diagDumpResumen);
        if (dryRun) {
            manifest.step("dry-run",
                    "params.dryRun=true — no fill, no Continuar; navigation + scrape + match still execute");
        }
        if (diagDumpResumen) {
            manifest.step("diag-dump-resumen",
                    "params.diag.dumpResumen=true — fills first row, opens Resumen, dumps HTML, Cancela; "
                            + "skips match loop and roster diff");
        }

        List<Canonical> canonicals = loadCanonical(bindings);
        if (canonicals.isEmpty()) {
            throw new IllegalStateException(
                    "ins-rt-virtual submit requires at least one canonical employee on "
                            + "params.source.submitRequest");
        }
        canonicalGrandTotal = canonicals.stream()
                .map(Canonical::salary)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        manifest.step("source",
                "canonicals=" + canonicals.size()
                        + " expectedTotal=" + canonicalGrandTotal
                        + " sourceCaptureEnvelopeId=" + sourceCaptureEnvelopeId);

        waitForPostLoginReady(page, bindings, manifest);
        openPolizaForClient(page, bindings, manifest);
        openPlanillaForEdit(page, bindings, manifest);
        // Bump rows-per-page to the maximum INS offers (200) so the entire
        // planilla renders on a single page. This lets saveRow anchor on
        // {@code :has-text(<cédula>)} directly without fighting the
        // toolbar search box (whose magnifier-button submit affordance
        // we couldn't reliably click — see 2026-05-04T19:49 / T20:11
        // failures) and without depending on stale page indices that
        // INS invalidates by reshuffling rows after every auto-save.
        // 200 covers any realistic NeoProc-managed payroll; if a future
        // firm exceeds it, this will surface as the loop iterating
        // canonicals that match no displayed row, which the matcher
        // already routes to MISSING_FROM_PORTAL.
        setPageSize(page, "200", manifest);
        saveDiagnosticScreenshot(page, bindings, "planilla-edit-state");
        log.info("ins-rt-virtual on planilla edit page; scraping rows");

        if (diagDumpResumen) {
            runDiagDumpResumen(page, canonicals, bindings, manifest);
            // Short-circuit the rest of beforeSteps. buildSubmitOutcome will
            // run with empty submittedRows / zero totals — the result envelope
            // is meaningless in diag mode; the value is the dumped HTML on disk.
            submittedRows = List.of();
            nameDriftSignals = List.of();
            rosterDiff = new RosterDiff(List.of(), List.of());
            portalReportedTotal = BigDecimal.ZERO;
            consecutivoTemporal = "";
            trabajadoresReportados = 0;
            return;
        }

        List<SubmitResultBody.SubmittedRow> matchedRows = new ArrayList<>();
        List<RosterDiff.MissingFromPortal> missingFromPortal = new ArrayList<>();
        List<SubmitResultBody.Signal> drifts = new ArrayList<>();
        List<PortalRow> displayedAll = scrapeAllPages(page, manifest);
        boolean[] displayedMatched = new boolean[displayedAll.size()];
        List<SubmitResultBody.Signal> fillFails = new ArrayList<>();

        for (Canonical c : canonicals) {
            Optional<EmployeeMatcher.MatchResult> matched = findMatchingRow(c, displayedAll);
            if (matched.isEmpty()) {
                missingFromPortal.add(new RosterDiff.MissingFromPortal(
                        c.id(), c.name(), c.salary(), c.attributes()));
                log.info("match-skip canonicalId={} (no portal row, or duplicated cédula with name mismatch)", c.id());
                manifest.step("match-skip",
                        "canonicalId=" + c.id() + " (no portal row, or duplicated cédula with name mismatch)");
                continue;
            }
            int globalIdx = matched.get().index();
            boolean nameConfirmed = matched.get().nameConfirmed();
            displayedMatched[globalIdx] = true;
            PortalRow row = displayedAll.get(globalIdx);

            if (nameConfirmed) {
                log.info("match-hit canonicalId={} -> portalIdentification={} salary={}",
                        c.id(), row.identification(), c.salary());
            } else {
                // Cédula matched uniquely on the planilla but the names
                // diverged — apply the salary (cédula is the primary key)
                // and surface a NAME_DRIFT signal so HITL can confirm it's
                // the same person before final submit.
                log.warn("match-hit-name-drift canonicalId={} canonicalName='{}' portalName='{}' (cédula unique — applying salary)",
                        c.id(), c.name(), row.name());
                manifest.step("match-hit-name-drift",
                        "canonicalId=" + c.id()
                                + " canonicalName='" + c.name() + "'"
                                + " portalName='" + row.name() + "'");
                drifts.add(new SubmitResultBody.Signal(
                        "NAME_DRIFT",
                        null, null, null,
                        row.identification(),
                        c.name(),                  // canonical name
                        row.name(),                // portal name
                        null, null));
            }

            // saveRow is wrapped: a single row's failure (search returned
            // 0/many, fill timeout, INS reshuffled the row out from under
            // us, etc.) records a FILL_FAILED signal and the loop moves
            // on. Aborting on the first failure left every later
            // canonical un-saved AND surfaced a useless terminal-fail
            // result envelope; HITL would rather see "12 of 14 saved, 2
            // need attention" than a bare exception. Status routes to
            // PARTIAL when fillFails is non-empty.
            try {
                saveRow(page, row, c.salary(), bindings, manifest);
                matchedRows.add(new SubmitResultBody.SubmittedRow(
                        row.identification(), row.name(), c.salary()));
            } catch (RuntimeException saveEx) {
                String reason = saveEx.getClass().getSimpleName()
                        + ": " + saveEx.getMessage();
                log.warn("save-failed canonicalId={} portalCedula={} reason={}",
                        c.id(), row.identification(), reason, saveEx);
                manifest.step("save-failed",
                        "canonicalId=" + c.id()
                                + " portalCedula=" + row.identification()
                                + " expectedSalary=" + c.salary().toPlainString()
                                + " reason=" + reason);
                fillFails.add(new SubmitResultBody.Signal(
                        "FILL_FAILED", null, null, null,
                        row.identification(),
                        c.name(),
                        c.salary(),
                        null));
            }
        }

        // Anything portal-side that we never matched and that has a
        // non-zero current salary is a deregistration candidate. Rows
        // with zero current salary are ignored — placeholders the portal
        // already considers inactive.
        List<RosterDiff.MissingFromPayroll> missingFromPayroll = new ArrayList<>();
        for (int i = 0; i < displayedAll.size(); i++) {
            if (displayedMatched[i]) continue;
            PortalRow row = displayedAll.get(i);
            BigDecimal lastKnown = row.currentSalary();
            if (lastKnown == null || lastKnown.signum() == 0) continue;
            missingFromPayroll.add(new RosterDiff.MissingFromPayroll(
                    row.identification(), row.name(), lastKnown));
        }

        rosterDiff = new RosterDiff(missingFromPortal, missingFromPayroll);
        submittedRows = List.copyOf(matchedRows);
        nameDriftSignals = List.copyOf(drifts);
        fillFailureSignals = List.copyOf(fillFails);
        log.info("roster-diff missingFromPortal={} missingFromPayroll={} nameDrift={} fillFailed={} expected={}",
                missingFromPortal.size(), missingFromPayroll.size(),
                nameDriftSignals.size(), fillFailureSignals.size(), canonicalGrandTotal);
        manifest.step("roster-diff",
                "missingFromPortal=" + missingFromPortal.size()
                        + " missingFromPayroll=" + missingFromPayroll.size()
                        + " nameDrift=" + nameDriftSignals.size()
                        + " fillFailed=" + fillFailureSignals.size()
                        + " expected=" + canonicalGrandTotal);

        // Open the Resumen dialog for the post-save totals readout.
        // Continuar fires regardless of dryRun — the dialog is read-only
        // until the user clicks Presentar / Cancelar, and we always
        // Cancelar so the planilla is never committed.
        DialogTotals totals = openResumenAndScrape(page, manifest);
        portalReportedTotal = totals.totalSalarios() == null
                ? BigDecimal.ZERO : totals.totalSalarios();
        consecutivoTemporal = totals.consecutivoTemporal();
        trabajadoresReportados = totals.trabajadoresReportados();
        manifest.step("post-save-totals",
                "consecutivoTemporal=" + consecutivoTemporal
                        + " trabajadores=" + trabajadoresReportados
                        + " portalReported=" + portalReportedTotal);

        // Always Cancelar — Presentar planilla is HITL-only territory.
        // safeClick double-checks: if a refactor accidentally swapped the
        // selector for the Presentar button, it would refuse and throw.
        // In dryRun mode we never opened the Resumen dialog, so there's
        // no Cancelar to click — the cloned planilla just stays in
        // "Planillas guardadas" with whatever state it cloned from.
        if (!dryRun) {
            safeClick(page.locator(CANCELAR_BUTTON), "Cancelar");
            page.waitForLoadState(LoadState.LOAD);
            manifest.step("cancelar", "dismissed Resumen dialog without Presentar");
        } else {
            manifest.step("cancelar-DRYRUN",
                    "skipped Cancelar (Resumen dialog never opened in dryRun); "
                            + "cloned planilla left in Planillas guardadas for manual cleanup");
        }
    }

    @Override
    protected SubmitOutcome buildSubmitOutcome(Map<String, String> scraped,
                                               List<Map<String, String>> scrapedRows,
                                               Map<String, String> bindings,
                                               PortalCredentials credentials,
                                               RunManifest manifest) {

        // Same status routing as CCSS Sicere: roster diff, name drift,
        // or any per-row save failure routes to PARTIAL (HITL needs to
        // resolve the gap); totals divergence with empty diff routes to
        // MISMATCH; otherwise SUCCESS. Reconciliation gates on
        // portalReportedTotal — the value INS displays in the Resumen
        // dialog after auto-save settles.
        boolean totalsMatch = canonicalGrandTotal.compareTo(portalReportedTotal) == 0;
        String status;
        if (!rosterDiff.isEmpty() || !nameDriftSignals.isEmpty() || !fillFailureSignals.isEmpty()) {
            status = EnvelopeStatus.PARTIAL;
        } else if (!totalsMatch) {
            status = EnvelopeStatus.MISMATCH;
        } else {
            status = EnvelopeStatus.SUCCESS;
        }

        manifest.step("verify",
                "status=" + status
                        + " expected=" + canonicalGrandTotal
                        + " portalReported=" + portalReportedTotal
                        + " rosterDiff.empty=" + rosterDiff.isEmpty()
                        + " nameDrift=" + nameDriftSignals.size()
                        + " fillFailed=" + fillFailureSignals.size());

        SubmitResultBody.Review review = buildReview(
                status, canonicalGrandTotal, portalReportedTotal, rosterDiff,
                nameDriftSignals, fillFailureSignals);

        // INS Consecutivo temporal is a diagnostic id pre-Presentar; it
        // promotes to a permanent planilla number after HITL final-submit.
        // Capture it on PortalConfirmation so HITL has the context for the
        // resubmit/escalate decision.
        SubmitResultBody.PortalConfirmation confirmation =
                (consecutivoTemporal != null && !consecutivoTemporal.isBlank())
                        ? new SubmitResultBody.PortalConfirmation(
                                consecutivoTemporal,
                                Instant.now(),
                                // Schema requires a non-null string; pass
                                // the verbatim Resumen dialog text so HITL
                                // sees the same fields INS rendered. Empty
                                // string fallback covers the path where
                                // the dialog scrape didn't populate it
                                // (e.g. dryRun short-circuit upstream).
                                resumenRawText == null ? "" : resumenRawText)
                        : null;

        SubmitResultBody body = new SubmitResultBody(
                status,
                confirmation,
                new SubmitResultBody.Totals(
                        "CRC",
                        portalReportedTotal,            // grandTotal (legacy field)
                        submittedRows.size(),
                        canonicalGrandTotal,            // expected
                        portalReportedTotal),           // portalReported (explicit)
                submittedRows,
                rosterDiff,
                null,
                review);

        String runId = require(bindings, "runtime.runId");
        String businessKey = bindings.getOrDefault(
                "params.businessKey", "ins-rt-virtual::" + clientIdentifier + "::" + runId);

        return new SubmitOutcome(
                status,
                body,
                "ins-rt-virtual",
                businessKey,
                "agent-worker/ins-rt-virtual",
                sourceCaptureEnvelopeId,
                clientIdentifier);
    }

    @Override
    public void afterCapture(Page page, Map<String, String> bindings, RunManifest manifest) {
        // Success-path teardown. PortalRunService swallows any exception
        // thrown from this hook, so a logout failure cannot flip a SUCCESS
        // run to FAILED. The same helper is invoked from the failure path
        // in beforeSteps so error cycles don't leak the shared session.
        performLogoutBestEffort(page, manifest, "logout");
    }

    /**
     * Click whatever "Cerrar sesión" affordance INS exposes on the
     * current page. Tries the left-sidebar link first (visible from any
     * post-login state, including planilla edit / Resumen dialog open),
     * then falls back to the top-right user-menu flow that the codegen
     * recording captured. Always non-fatal: if both paths fail we log
     * and move on, leaving the JWT to expire on its own — leaking a
     * short-lived session is annoying (next run dismisses a "concurrent
     * session detected" modal) but never blocks correctness.
     *
     * @param stepLabel manifest step name on success — typically
     *                  {@code "logout"} for the success path and
     *                  {@code "logout-on-error"} so the manifest makes
     *                  it obvious which path the cleanup ran from.
     */
    private void performLogoutBestEffort(Page page, RunManifest manifest, String stepLabel) {
        if (page == null) return;
        try {
            // Sidebar path — verified visible in every post-login screenshot
            // captured 2026-05-04. The exact-text match avoids accidentally
            // hitting a button that merely contains "Cerrar" (e.g. a modal's
            // close button). 3s click timeout so we don't burn 30s of
            // Playwright actionability waiting on a stale element.
            Locator sidebar = page.locator(":visible:text-is(\"Cerrar sesión\")").first();
            if (sidebar.count() > 0) {
                try {
                    sidebar.click(new Locator.ClickOptions().setTimeout(3_000));
                    page.waitForLoadState(LoadState.LOAD);
                    manifest.step(stepLabel, "sidebar Cerrar sesión");
                    return;
                } catch (RuntimeException ignored) {
                    // Fall through to top-right user-menu attempt.
                }
            }
            // Top-right user-menu path — the codegen recording's original.
            page.locator(".buttons-user-item-header").first()
                    .click(new Locator.ClickOptions().setTimeout(3_000));
            page.locator("button:has-text(\"Cerrar sesión\")").first()
                    .click(new Locator.ClickOptions().setTimeout(3_000));
            page.waitForLoadState(LoadState.LOAD);
            manifest.step(stepLabel, "user-menu -> Cerrar sesión");
        } catch (RuntimeException e) {
            log.warn("ins-rt-virtual logout failed (non-fatal)", e);
            manifest.step(stepLabel + "-skip", "best-effort logout failed: " + e.getMessage());
        }
    }

    // --- Navigation ---------------------------------------------------------

    /**
     * After auth steps, INS's BFF returns {@code otrasSessiones: true}
     * whenever the shared-creds account has any other live session (which
     * is most of the time — multiple operators share these credentials).
     * The SPA reacts by rendering a "Detectamos otra sesión activa"
     * confirmation modal with two buttons: {@code Iniciar aquí} (take over
     * the session, killing the other one) and {@code No} (cancel, stay
     * logged out).
     *
     * <p>The codegen recording missed this modal because the operator
     * recorded from a fresh-only session. Without dismissing it, the SPA
     * never bootstraps the JWT and any subsequent /ListPoliza navigation
     * lands on /UnAuthorized.
     *
     * <p>Strategy: poll for either the conflict modal or the dashboard
     * cards directly. When the modal appears, click {@code Iniciar aquí}
     * to take over the session, then wait for the cards. Anchor the modal
     * by visible text rather than role/id because Mantine/PrimeReact
     * dialog wrappers are class-hashed and not stable across releases.
     */
    private void waitForPostLoginReady(Page page,
                                       Map<String, String> bindings,
                                       RunManifest manifest) {
        page.waitForLoadState(LoadState.LOAD);

        Locator cards = page.locator(POLIZA_CARD_SELECTOR_PREFIX);
        Locator conflictModal = page.locator(
                "[role=\"dialog\"]:has-text(\"Detectamos otra sesión activa\")");

        // Phase 1 — wait up to 20s for either signal. Login API takes
        // ~1.5s and the SPA needs another second or so to render the
        // modal, so a 500ms poll picks the state up reliably without
        // chewing CPU on a tight loop.
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (cards.count() > 0) {
                manifest.step("post-login", "dashboard cards visible (no session conflict)");
                return;
            }
            if (conflictModal.count() > 0) {
                handleSessionConflictModal(page, bindings, manifest);
                break;
            }
            page.waitForTimeout(500);
        }

        // Phase 2 — after dismissing the conflict modal (or timing out
        // on phase 1 with no signal), wait for cards to render. The
        // SPA navigates to /ListPoliza or /WorkSpace internally after
        // the conflict modal closes; either path renders the cards.
        try {
            cards.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(15_000));
            manifest.step("post-login", "dashboard cards visible at " + page.url());
        } catch (RuntimeException timeout) {
            saveDiagnosticScreenshot(page, bindings, "post-login-stall");
            String url = page.url();
            throw new IllegalStateException(
                    "ins-rt-virtual: post-login dashboard cards never rendered. "
                            + "url=" + url + " — see post-login-stall.png in the run "
                            + "directory. Auth completed at the BFF level (LoginBFFSecure 200) "
                            + "but the SPA didn't transition to the dashboard; check whether "
                            + "the conflict modal selector still matches.",
                    timeout);
        }
    }

    /**
     * Click "Iniciar aquí" on the "Detectamos otra sesión activa" modal,
     * which takes over the session (closing whatever other session
     * triggered the conflict). The modal's secondary button is "No"
     * which would cancel the login — never click that.
     */
    private void handleSessionConflictModal(Page page,
                                            Map<String, String> bindings,
                                            RunManifest manifest) {
        Locator modal = page.locator(
                "[role=\"dialog\"]:has-text(\"Detectamos otra sesión activa\")").first();
        saveDiagnosticScreenshot(page, bindings, "session-conflict-modal");
        manifest.step("session-conflict",
                "Detectamos otra sesión activa modal detected — clicking Iniciar aquí");
        log.info("session-conflict modal detected; clicking 'Iniciar aquí' to take over");
        // :has-text matches anywhere; visible-only filter avoids any
        // hidden duplicate. The modal's "No" cancel button has a
        // distinct label so there's no ambiguity here.
        Locator iniciarAqui = modal.locator("button:visible:has-text(\"Iniciar aquí\")").first();
        iniciarAqui.click();
        page.waitForLoadState(LoadState.LOAD);
    }

    /**
     * Resolve a sidebar / page button by its visible label, preferring
     * the affordance Playwright's role engine identifies as a button
     * (<button>, role="button", input type=button) and falling back to
     * any visible element whose direct text content equals the label.
     * Returns null if neither yields a visible match within 10s — the
     * caller then takes a diagnostic screenshot and fails with context.
     */
    private static Locator clickableByText(Page page, String label) {
        Locator byRole = page.locator("role=button[name=\"" + label + "\"]").first();
        try {
            byRole.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(10_000));
            return byRole;
        } catch (RuntimeException ignored) {
            // Theme variant — try exact text match on any element.
        }
        Locator byText = page.locator(":visible:text-is(\"" + label + "\")").first();
        try {
            byText.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(2_000));
            return byText;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Diagnostic-only flow: drops onto the planilla edit page after
     * openPlanillaForEdit, fills the first visible row with the first
     * canonical's salary, clicks Continuar to open the Resumen dialog,
     * then dumps three pieces of DOM to disk so we can harden selectors
     * without burning an E2E attempt:
     *
     * <ol>
     *   <li>{@code diag-spot1-salary-input.html} — the first salary
     *       {@code <input>}'s outerHTML, plus a sibling-walk of its
     *       containing row to see how INS lays the row out.</li>
     *   <li>{@code diag-spot2-resumen-dialog.html} + .txt + screenshot —
     *       the Resumen de planilla dialog's full outer HTML and rendered
     *       text, so we can pin labels (Total de salarios, Consecutivo
     *       temporal, etc.) and value cell selectors.</li>
     *   <li>{@code diag-spot3-cancelar-candidates.html} — every visible
     *       button whose text matches /cancel|cerrar|volver/i, with its
     *       outerHTML and a parent-chain crumb, so we can pick the right
     *       Cancelar without colliding with a snackbar/header Cancelar.</li>
     * </ol>
     *
     * Then clicks Cancelar to dismiss the dialog (no Presentar — the
     * FORBIDDEN_BUTTON_LABELS denylist still applies). The cloned planilla
     * is left with the one filled row exactly like a normal save-only
     * run; the operator cleans it up from "Planillas guardadas" later.
     */
    private void runDiagDumpResumen(Page page,
                                    List<Canonical> canonicals,
                                    Map<String, String> bindings,
                                    RunManifest manifest) {
        // --- spot 1: salary input + row HTML --------------------------------
        Locator inputs = page.locator("input[name=\"salario\"]");
        if (inputs.count() == 0) {
            saveDiagnosticScreenshot(page, bindings, "diag-no-salary-inputs");
            throw new IllegalStateException(
                    "diag.dumpResumen: no input[name=\"salario\"] visible on the edit page");
        }
        Locator firstInput = inputs.first();
        String inputHtml = (String) firstInput.evaluate("el => el.outerHTML");
        saveDiagFile(bindings, "diag-spot1-salary-input.html", inputHtml);
        // Walk up to the first ancestor whose textContent contains a
        // cédula-shaped digit run (same heuristic scrapeCurrentPage uses)
        // and dump its outerHTML — that's the row container.
        String rowHtml = (String) firstInput.evaluate(
                "(el) => { let p = el.parentElement; "
                        + "while (p && !/\\d{9,12}/.test(p.textContent || '')) p = p.parentElement; "
                        + "return p ? p.outerHTML : ''; }");
        saveDiagFile(bindings, "diag-spot1-salary-row.html", rowHtml);
        saveDiagnosticScreenshot(page, bindings, "diag-spot1-edit-page");
        manifest.step("diag-spot1", "dumped first salary input + row outerHTML");

        // --- fill the first row so Continuar has something to summarise -----
        Canonical first = canonicals.get(0);
        BigDecimal diagSalary = first.salary();
        log.info("diag: filling first salary input with canonical {} salary={}",
                first.id(), diagSalary);
        firstInput.scrollIntoViewIfNeeded();
        firstInput.click();
        firstInput.fill(formatSalaryForInput(diagSalary));
        firstInput.press("Tab");
        page.waitForTimeout(800);  // auto-save toast settles
        // Capture what the input actually holds after blur — tells us
        // whether INS reformats US-comma ("1,127,500") or expects EU.
        String postBlurValue = "";
        try {
            postBlurValue = firstInput.inputValue();
        } catch (RuntimeException ignored) {}
        saveDiagFile(bindings, "diag-spot1-input-value-post-blur.txt",
                "filled='" + formatSalaryForInput(diagSalary)
                        + "'\npostBlur='" + postBlurValue + "'");
        manifest.step("diag-spot1-fill",
                "filled=" + formatSalaryForInput(diagSalary)
                        + " postBlur='" + postBlurValue + "'");

        // --- spot 2: Resumen dialog -----------------------------------------
        log.info("diag: clicking Continuar to open Resumen dialog");
        safeClick(page.locator(CONTINUAR_BUTTON), "Continuar");
        // Wait for any role=dialog to materialise; the existing
        // RESUMEN_DIALOG selector requires "Resumen de planilla" text and
        // would over-constrain if the title differs.
        try {
            page.waitForSelector("[role=\"dialog\"]:visible",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(15_000));
        } catch (RuntimeException timeout) {
            saveDiagnosticScreenshot(page, bindings, "diag-spot2-no-dialog");
            saveDiagFile(bindings, "diag-spot2-no-dialog-pageHtml.html",
                    (String) page.evaluate("() => document.body.outerHTML"));
            throw new IllegalStateException(
                    "diag.dumpResumen: no role=dialog appeared after Continuar — "
                            + "see diag-spot2-no-dialog.png", timeout);
        }
        // The Resumen dialog opens with a loader spinner ("modal-body
        // > .loader > .loading") and only renders the totals/buttons after
        // an async fetch settles. Reading immediately captures the
        // skeleton — confirmed empirically on the 14:44 diag run where
        // diag-spot2-resumen-dialog.txt came back 0 bytes. Poll until
        // either the loader disappears or the dialog text grows past a
        // skeletal threshold.
        long deadline = System.currentTimeMillis() + 30_000;
        boolean loaded = false;
        while (System.currentTimeMillis() < deadline) {
            int loaderCount = page.locator("[role=\"dialog\"]:visible .loader:visible").count();
            String txt;
            try {
                txt = page.locator("[role=\"dialog\"]:visible").first().innerText();
            } catch (RuntimeException e) { txt = ""; }
            if (loaderCount == 0 && txt.length() > 30) {
                loaded = true;
                break;
            }
            page.waitForTimeout(500);
        }
        if (!loaded) {
            log.warn("diag: Resumen dialog loader never cleared in 30s — dumping current state anyway");
        }
        // Prefer the Resumen-anchored locator; fall back to any visible dialog.
        Locator resumen = page.locator(RESUMEN_DIALOG).first();
        if (resumen.count() == 0) {
            resumen = page.locator("[role=\"dialog\"]:visible").first();
        }
        String dlgHtml = (String) resumen.evaluate("el => el.outerHTML");
        String dlgText = resumen.innerText();
        saveDiagFile(bindings, "diag-spot2-resumen-dialog.html", dlgHtml);
        saveDiagFile(bindings, "diag-spot2-resumen-dialog.txt", dlgText);
        saveDiagnosticScreenshot(page, bindings, "diag-spot2-resumen");
        manifest.step("diag-spot2",
                "loaded=" + loaded + " dumped Resumen dialog outerHTML ("
                        + dlgHtml.length() + " bytes) text=" + dlgText.length() + " bytes");

        // --- spot 3: Cancelar candidates ------------------------------------
        Locator visButtons = page.locator("button:visible");
        int btnCount = visButtons.count();
        StringBuilder candidates = new StringBuilder();
        candidates.append("Visible buttons matching /cancel|cerrar|volver/i (case-insensitive):\n\n");
        int matched = 0;
        for (int i = 0; i < btnCount; i++) {
            Locator b = visButtons.nth(i);
            String text;
            try {
                text = b.innerText().trim();
            } catch (RuntimeException e) {
                continue;
            }
            if (text.isEmpty()) continue;
            String low = text.toLowerCase(Locale.ROOT);
            if (!(low.contains("cancel") || low.contains("cerrar") || low.contains("volver"))) {
                continue;
            }
            String html;
            try {
                html = (String) b.evaluate("el => el.outerHTML");
            } catch (RuntimeException e) {
                continue;
            }
            String parentChain;
            try {
                parentChain = (String) b.evaluate(
                        "(el) => { const crumbs = []; let p = el; "
                                + "for (let i = 0; i < 6 && p && p.tagName; i++) { "
                                + "  const id = p.id ? '#' + p.id : ''; "
                                + "  const cls = p.className && typeof p.className === 'string' "
                                + "      ? '.' + p.className.trim().split(/\\s+/).slice(0, 3).join('.') : ''; "
                                + "  crumbs.unshift(p.tagName.toLowerCase() + id + cls); "
                                + "  p = p.parentElement; "
                                + "} return crumbs.join(' > '); }");
            } catch (RuntimeException e) {
                parentChain = "(crumb-failed)";
            }
            candidates.append("--- candidate[").append(i).append("] text='").append(text).append("' ---\n")
                    .append("parentChain: ").append(parentChain).append("\n")
                    .append(html).append("\n\n");
            matched++;
        }
        if (matched == 0) {
            candidates.append("(no visible button matched /cancel|cerrar|volver/i — full button list follows)\n\n");
            for (int i = 0; i < Math.min(btnCount, 50); i++) {
                String text;
                try {
                    text = visButtons.nth(i).innerText().trim();
                } catch (RuntimeException e) { continue; }
                if (text.isEmpty()) continue;
                candidates.append("[").append(i).append("] '").append(text).append("'\n");
            }
        }
        saveDiagFile(bindings, "diag-spot3-cancelar-candidates.txt", candidates.toString());
        manifest.step("diag-spot3", "dumped " + matched + " Cancelar/Cerrar/Volver candidate(s)");

        // --- back out cleanly via Cancelar ----------------------------------
        try {
            safeClick(page.locator(CANCELAR_BUTTON), "Cancelar");
            page.waitForLoadState(LoadState.LOAD);
            manifest.step("diag-cancelar", "dismissed Resumen dialog via existing CANCELAR_BUTTON selector");
        } catch (RuntimeException e) {
            log.warn("diag: Cancelar click failed — leaving dialog open. spot3 dump should reveal why.", e);
            manifest.step("diag-cancelar-fail", "current CANCELAR_BUTTON selector did not match: " + e.getMessage());
        }
    }

    /**
     * Write a UTF-8 text artifact next to the run's screenshots/manifest.
     * Used by the diag.dumpResumen flow; non-fatal on failure (worker
     * shouldn't die over a diagnostic write that would only delay diag
     * iteration anyway).
     */
    private static void saveDiagFile(Map<String, String> bindings,
                                     String name,
                                     String content) {
        try {
            String runDirStr = bindings.get("runtime.runDir");
            if (runDirStr == null) {
                log.warn("diag file {} not written — runtime.runDir binding missing", name);
                return;
            }
            Path file = Paths.get(runDirStr).resolve(name);
            java.nio.file.Files.writeString(file, content == null ? "" : content);
            log.info("diag file written: {} ({} bytes)", file,
                    content == null ? 0 : content.length());
        } catch (Exception e) {
            log.warn("failed to write diag file {}", name, e);
        }
    }

    private void saveDiagnosticScreenshot(Page page,
                                          Map<String, String> bindings,
                                          String name) {
        try {
            String runDirStr = bindings.get("runtime.runDir");
            if (runDirStr == null) return;
            Path file = Paths.get(runDirStr).resolve(name + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(file).setFullPage(true));
            log.info("diagnostic screenshot saved: {}", file);
        } catch (RuntimeException e) {
            log.warn("failed to save diagnostic screenshot {}", name, e);
        }
    }

    /**
     * Find the póliza card whose "Identificación" cell text matches the
     * configured cédula jurídica, then click its Ver póliza button. Hard
     * fails on zero or multiple matches: zero means the shared-creds
     * account doesn't see this firm's póliza (credential scope drift);
     * multiple means a firm has more than one póliza, which the binding
     * contract can't disambiguate yet (would need params.policyNumber).
     */
    private void openPolizaForClient(Page page,
                                     Map<String, String> bindings,
                                     RunManifest manifest) {
        // Praxis emits the cédula jurídica in dashed legal form
        // ("3-101-680139"); INS card text varies — observed digits-only
        // in operator screenshots, but the live render may carry dashes
        // or non-breaking spaces. Strategy: pull innerText from every
        // card, strip non-digits, compare to the normalized canonical.
        // CredentialsProvider#normalizeClientId is the canonical helper
        // for the canonical side; we mirror its strip-non-digits semantics
        // on the card text via the DIGITS_ONLY pattern.
        String canonicalDigits = CredentialsProvider.normalizeClientId(clientIdentifier);
        // [id^="idFooterCard_"] selects only the FOOTER element of each
        // card (the one wrapping the "Ver póliza" button) — its innerText
        // is just "Ver póliza", not the card body. The cédula and firm
        // name live in sibling/parent elements. Walk up to the closest
        // ancestor whose subtree contains "Identificación" — that's the
        // card root and the natural anchor for the cédula match.
        Locator footers = page.locator(POLIZA_CARD_SELECTOR_PREFIX);
        int total = footers.count();
        log.info("poliza dashboard: {} footer cards visible; searching for cédula={} (normalized={})",
                total, clientIdentifier, canonicalDigits);
        manifest.step("poliza-search",
                "totalCards=" + total + " canonical=" + canonicalDigits);

        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            Locator card = footers.nth(i).locator(
                    "xpath=ancestor::*[contains(normalize-space(.), 'Identificación')][1]");
            String text;
            try {
                text = card.innerText();
            } catch (RuntimeException e) {
                continue;
            }
            String digits = text.replaceAll("[^0-9]", "");
            if (digits.contains(canonicalDigits)) {
                matches.add(i);
            }
            if (i < 4) {
                log.info("card[{}] cédula-digits-head='{}' textHead='{}'",
                        i, digits.length() > 20 ? digits.substring(0, 20) : digits,
                        text.replaceAll("\\s+", " ").substring(0, Math.min(120, text.length())));
            }
        }
        if (matches.isEmpty()) {
            saveDiagnosticScreenshot(page, bindings, "poliza-no-match");
            throw new IllegalStateException(
                    "ins-rt-virtual: no póliza card matched clientIdentifier=" + clientIdentifier
                            + " (canonical digits=" + canonicalDigits + ") across " + total
                            + " visible cards. Either the shared-creds account doesn't see "
                            + "this firm's póliza, or the firm's cédula jurídica was misregistered "
                            + "on Praxis side. See poliza-no-match.png and the card[*] log lines.");
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "ins-rt-virtual: " + matches.size() + " póliza cards matched clientIdentifier="
                            + clientIdentifier + " (canonical digits=" + canonicalDigits
                            + "). Multi-policy firms aren't supported by the current binding "
                            + "contract — extend PortalOnboarding.md's listener-bindings table "
                            + "with params.policyNumber to disambiguate, then update this adapter "
                            + "to use it.");
        }
        // Click the matching footer's "Ver póliza" button directly — the
        // footer is what carries the click target, not the card root.
        Locator footer = footers.nth(matches.get(0));
        manifest.step("poliza-select",
                "matched 1 card at index=" + matches.get(0) + " for cédula=" + canonicalDigits);
        safeClick(footer.locator("button:has-text(\"Ver póliza\")"), "Ver póliza");
        page.waitForLoadState(LoadState.LOAD);

        // First-time access shows a welcome dialog with a single icon
        // close button. Skip past it if visible — the recording dismisses
        // it via getByRole('dialog').getByRole('button', { name: 'icon' }).
        Locator welcome = page.locator("[role=\"dialog\"] button:visible").first();
        if (welcome.count() > 0) {
            try {
                welcome.click(new Locator.ClickOptions().setTimeout(2_000));
                manifest.step("welcome-dialog", "dismissed");
            } catch (RuntimeException ignored) {
                // No dialog to close; proceed.
            }
        }
    }

    private void openPlanillaForEdit(Page page,
                                     Map<String, String> bindings,
                                     RunManifest manifest) {
        // Sidebar accordion: a "Planilla" menu item expands to reveal
        // Preparar planilla, Historial de planillas, Planillas guardadas.
        // The bare text "Planilla" appears as a substring in several
        // sidebar labels; match by exact text (text-is) so we hit only
        // the parent menu and not its children. The recording observed
        // the menu rendered with icons on either side of the label, so
        // accessible-name match would also need icon-tolerant regexp —
        // text-is over the visible text node is simpler.
        Locator planillaMenu = page.locator("button:visible:text-is(\"Planilla\")").first();
        if (planillaMenu.count() == 0) {
            // Fallback: the sidebar may render the menu item as an <a> or
            // a <div> instead of a <button>. Match anything visible whose
            // direct text content equals "Planilla".
            planillaMenu = page.locator(":visible:text-is(\"Planilla\")").first();
        }
        safeClick(planillaMenu, "Planilla menu");
        page.waitForTimeout(800);  // accordion expand animation

        // The codegen recording used getByRole('button', { name: 'Preparar
        // planilla' }) — the items therefore expose a button role
        // (either <button> or role="button"). Use Playwright's role=
        // selector so we hit exactly that affordance and not an outer
        // text-containing wrapper. text-is is the secondary fallback for
        // theme variants that drop the role attribute.
        Locator preparar = clickableByText(page, "Preparar planilla");
        if (preparar == null) {
            saveDiagnosticScreenshot(page, bindings, "preparar-planilla-not-found");
            throw new IllegalStateException(
                    "ins-rt-virtual: Preparar planilla never appeared after expanding "
                            + "the Planilla menu. See preparar-planilla-not-found.png — "
                            + "the menu may not have expanded, or sub-items are rendered "
                            + "with neither a button role nor an exact-text node.");
        }
        safeClick(preparar, "Preparar planilla");
        page.waitForLoadState(LoadState.LOAD);

        // INS renamed "Nueva planilla" to a longer descriptive label after
        // a UI refresh observed 2026-05-04: "Cargar lista de trabajadores
        // de periodos anteriores" (load workers from previous periods —
        // identical intent and same picker dialog opens). Try the legacy
        // label first for backward-compat, fall through to the new one.
        // Both labels live inside the "Acciones rápidas" dropdown when the
        // poliza is in en-edición state.
        //
        // CRITICAL: the dropdown's *sibling* item is "Presentar planilla
        // sin trabajadores", which would file an empty planilla on click.
        // Never click it. Belt-and-suspenders: it's also in
        // FORBIDDEN_BUTTON_LABELS, and we anchor on the specific
        // "Cargar lista..." text rather than any visible dropdown item.
        Locator nueva = clickableByText(page, "Nueva planilla");
        if (nueva == null) {
            nueva = clickableByText(page, "Cargar lista de trabajadores de periodos anteriores");
        }
        if (nueva == null) {
            log.info("Cargar/Nueva planilla not directly visible — opening Acciones rápidas dropdown");
            Locator acciones = clickableByText(page, "Acciones rápidas");
            if (acciones != null) {
                safeClick(acciones, "Acciones rápidas");
                page.waitForTimeout(500);
                nueva = clickableByText(page, "Nueva planilla");
                if (nueva == null) {
                    nueva = clickableByText(page, "Cargar lista de trabajadores de periodos anteriores");
                }
            }
        }
        if (nueva == null) {
            saveDiagnosticScreenshot(page, bindings, "nueva-planilla-not-found");
            // Dump every visible button label to the manifest so the next
            // iteration can pin the right selector without another screenshot.
            Locator allButtons = page.locator("button:visible, [role=\"button\"]:visible");
            int btnCount = allButtons.count();
            StringBuilder labels = new StringBuilder();
            for (int i = 0; i < Math.min(btnCount, 30); i++) {
                String t = textOrEmpty(allButtons.nth(i)).replaceAll("\\s+", " ");
                if (!t.isEmpty()) labels.append('[').append(i).append(":'").append(t).append("'] ");
            }
            log.info("visible buttons on Preparar planilla page: {}", labels);
            manifest.step("nueva-planilla-not-found",
                    "buttons=" + labels.toString());
            throw new IllegalStateException(
                    "ins-rt-virtual: neither 'Nueva planilla' nor "
                            + "'Cargar lista de trabajadores de periodos anteriores' "
                            + "found on the Preparar planilla page (or inside Acciones "
                            + "rápidas dropdown). See nueva-planilla-not-found.png and "
                            + "the manifest nueva-planilla-not-found step for the visible "
                            + "button list.");
        }
        safeClick(nueva, "Cargar/Nueva planilla");

        // The Nueva planilla dialog lists prior-period planillas as rows
        // with checkboxes. Operator confirmed the most recent prior is
        // always the source-template; check the first row's checkbox and
        // proceed. If INS later renders multiple priors, switch to a
        // params-driven row selector here (e.g. by params.priorPlanillaId).
        // INS uses custom-rendered checkbox components rather than native
        // <input type="checkbox">; role=checkbox covers both forms.
        page.waitForTimeout(800);  // dialog render + content fetch
        // Locate rows using the role=row engine, scoped under the dialog
        // via the >> chain operator (the role= engine can't be combined
        // directly with CSS — it needs an explicit chain).
        Locator firstRow = page.locator("[role=\"dialog\"]")
                .locator("role=row").first();
        if (firstRow.count() == 0) {
            // Fallback: <tr> inside the dialog (native table form).
            firstRow = page.locator("[role=\"dialog\"] tr").first();
        }
        if (firstRow.count() == 0) {
            saveDiagnosticScreenshot(page, bindings, "nueva-planilla-no-rows");
            // Dump dialog innerText to manifest for diagnostic
            Locator dlg = page.locator("[role=\"dialog\"]").first();
            String dlgText = dlg.count() > 0
                    ? dlg.innerText().replaceAll("\\s+", " ")
                    : "(no dialog visible)";
            log.info("Nueva planilla dialog innerText: {}",
                    dlgText.substring(0, Math.min(500, dlgText.length())));
            manifest.step("nueva-planilla-no-rows",
                    "dialogText=" + dlgText.substring(0, Math.min(200, dlgText.length())));
            throw new IllegalStateException(
                    "ins-rt-virtual: Nueva planilla dialog opened but no rows visible. "
                            + "See nueva-planilla-no-rows.png and the manifest "
                            + "nueva-planilla-no-rows step for the dialog text.");
        }
        // Click the row's checkbox via role-aware locator (handles native
        // <input> and custom-rendered checkbox components alike).
        firstRow.locator("role=checkbox").first().check();
        manifest.step("nueva-planilla", "selected first prior-planilla row to clone");

        safeClick(page.locator("button:has-text(\"Cargar lista seleccionada\")"),
                "Cargar lista seleccionada");
        // The codegen recording captured an "Aceptar" confirmation
        // dialog after Cargar — but empirically that only fires when
        // there's existing planilla state to overwrite. When the
        // operator is starting from a fresh poliza (no prior draft),
        // Cargar closes the picker dialog directly. Wait briefly for
        // Aceptar; click if present, proceed if not.
        Locator aceptar = page.locator("button:visible:has-text(\"Aceptar\")");
        try {
            aceptar.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(3_000));
            safeClick(aceptar, "Aceptar");
            manifest.step("aceptar-overwrite", "confirmed overwrite of prior planilla state");
        } catch (RuntimeException ignored) {
            manifest.step("aceptar-skip", "no Aceptar confirm dialog (fresh planilla state)");
        }
        page.waitForLoadState(LoadState.LOAD);

        // Confirm we're on the edit page by waiting for at least one
        // salary input. The page renders rows server-side after the
        // clone completes, so this also doubles as a clone-finished
        // signal.
        page.waitForSelector("input[name=\"salario\"]",
                new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(20_000));
        manifest.step("planilla-edit", "on editor; salario inputs visible");
    }

    // --- Pagination + per-row save -----------------------------------------

    private List<PortalRow> scrapeAllPages(Page page, RunManifest manifest) {
        // Read total pages from the .page-status span ("X de N") upfront,
        // same robustness pattern CCSS uses with its "Página X de Y"
        // paginator: counting upfront defends against the race where
        // gotoNextPage sees the new chrome before the new rows render.
        // INS renders the digits inside .page-status asynchronously
        // (the outer span shows up first with a partial "de" text);
        // poll briefly so we read the populated value, not the skeleton.
        int totalPages = pollTotalPages(page, 5_000);
        log.info("paginator reports totalPages={}", totalPages);

        List<PortalRow> all = new ArrayList<>();
        for (int pageIdx = 1; pageIdx <= totalPages; pageIdx++) {
            List<PortalRow> pageRows = scrapeCurrentPage(page, pageIdx);
            all.addAll(pageRows);
            log.info("scraped page {}/{} rows={} runningTotal={}",
                    pageIdx, totalPages, pageRows.size(), all.size());
            manifest.step("scrape-page",
                    "page=" + pageIdx + "/" + totalPages
                            + " rows=" + pageRows.size()
                            + " runningTotal=" + all.size());
            if (pageIdx < totalPages && !gotoNextPage(page)) {
                log.warn("expected to advance to page {} but Next was no-op; stopping",
                        pageIdx + 1);
                break;
            }
        }
        gotoFirstPage(page);
        return all;
    }

    /**
     * Poll {@link #readTotalPages} for up to {@code timeoutMs} or until
     * it returns a value > 1. INS renders the page-status text in two
     * passes (the "de" word lands first, then the digit spans populate);
     * a single read race with the digit pass and reports 1 page even
     * when there are 2+. This wrapper keeps the result-vs-1-page
     * ambiguity correct: a planilla genuinely on one page sees
     * timeoutMs of polling and returns 1, while a multi-page planilla
     * gets caught at the moment N>1 first becomes readable.
     */
    private static int pollTotalPages(Page page, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int last = 1;
        while (System.currentTimeMillis() < deadline) {
            int read = readTotalPages(page);
            if (read > 1) return read;
            last = read;
            page.waitForTimeout(250);
        }
        // Final-attempt diagnostic: dump every candidate so we can see
        // why the regex didn't catch the trabajadores paginator. Costs
        // one extra DOM walk on the last poll iteration only.
        Locator status = page.locator(PAGE_STATUS);
        int n = status.count();
        StringBuilder dump = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String t;
            try { t = status.nth(i).innerText(); } catch (RuntimeException e) { continue; }
            dump.append('[').append(i).append(":'").append(t.replaceAll("\\s+", "·")).append("'] ");
        }
        // Also try a broader DOM scan: any element whose text matches
        // the X-de-N pattern. If the trabajadores paginator uses a
        // different class, this catches it.
        Locator anyMatch = page.locator(":text-matches(\"\\\\d+\\\\s*de\\\\s*\\\\d+\")");
        int m = Math.min(anyMatch.count(), 5);
        StringBuilder scan = new StringBuilder();
        for (int i = 0; i < m; i++) {
            String t;
            try { t = anyMatch.nth(i).innerText(); } catch (RuntimeException e) { continue; }
            scan.append('[').append(i).append(":'")
                    .append(t.replaceAll("\\s+", "·").substring(0, Math.min(60, t.length())))
                    .append("'] ");
        }
        log.warn("pollTotalPages timed out at totalPages={}; .page-status candidates: {}; X-de-N in DOM: {}",
                last, dump, scan);
        return last;
    }

    /**
     * Walk every {@code .page-status} on the page (INS uses the same
     * class on multiple widgets — search results, error panel, the
     * trabajadores paginator, etc.) and return the largest N from any
     * "X de N" text. The trabajadores paginator is the one we care
     * about; its N is always ≥ all the static "1 de 1" indicators
     * elsewhere. Returns 1 if no match.
     */
    private static int readTotalPages(Page page) {
        Locator status = page.locator(PAGE_STATUS);
        int n = status.count();
        int maxPages = 1;
        String bestText = "";
        for (int i = 0; i < n; i++) {
            String text;
            try {
                text = status.nth(i).innerText().trim();
            } catch (RuntimeException e) {
                continue;
            }
            Matcher m = PAGE_STATUS_PATTERN.matcher(text);
            if (m.find()) {
                try {
                    int total = Integer.parseInt(m.group(2));
                    if (total > maxPages) {
                        maxPages = total;
                        bestText = text;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        if (!bestText.isEmpty()) {
            log.info("page-status[trabajadores]: '{}' -> totalPages={}", bestText, maxPages);
        }
        return maxPages;
    }


    private List<PortalRow> scrapeCurrentPage(Page page, int pageIdx) {
        // Anchor on the salary inputs themselves rather than presuming a
        // <tr>-based table — INS uses a div-grid layout. For each input,
        // walk up via JS to the nearest ancestor whose text contains a
        // cédula-shaped digit string; that ancestor is the row and its
        // textContent gives us cédula + name.
        Locator inputs = page.locator("input[name=\"salario\"]");
        int n = inputs.count();
        List<PortalRow> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Locator input = inputs.nth(i);
            String rowText;
            try {
                rowText = (String) input.evaluate(
                        "(el) => { let p = el.parentElement; "
                                + "while (p && !/\\d{9,12}/.test(p.textContent || '')) "
                                + "p = p.parentElement; "
                                + "return p ? (p.textContent || '') : ''; }");
            } catch (RuntimeException e) {
                continue;
            }
            if (rowText == null) continue;
            String compact = rowText.replaceAll("\\s+", " ").trim();
            // Pull the first cédula-shaped digit run as the identification.
            java.util.regex.Matcher m = CEDULA_PATTERN.matcher(compact);
            String cedula = m.find() ? m.group() : "";
            String name = "";
            if (!cedula.isEmpty()) {
                int idx = compact.indexOf(cedula);
                String tail = idx + cedula.length() < compact.length()
                        ? compact.substring(idx + cedula.length()).trim() : "";
                // Next non-empty token chunk is typically the name; cap
                // at a sensible upper bound to skip the salary trailing it.
                String[] toks = tail.split(" ");
                StringBuilder nb = new StringBuilder();
                int wordCount = 0;
                for (String t : toks) {
                    if (t.isEmpty()) continue;
                    if (t.matches("[\\d,.₡$]+") || t.matches("\\d{9,12}")) break;
                    if (nb.length() > 0) nb.append(' ');
                    nb.append(t);
                    if (++wordCount >= 5) break;
                }
                name = nb.toString();
            }
            BigDecimal currentSalary = readCurrentSalaryFromInput(input);
            out.add(new PortalRow(pageIdx, cedula, name, currentSalary));
            if (i < 2) {
                log.info("scrape-row[{}] page={} cedula='{}' name='{}' currentSalary={} rowTextHead='{}'",
                        i, pageIdx, cedula, name, currentSalary,
                        compact.substring(0, Math.min(120, compact.length())));
            }
        }
        return out;
    }

    private static BigDecimal readCurrentSalaryFromInput(Locator input) {
        try {
            String value = input.inputValue();
            return parseInsMoney(value);
        } catch (RuntimeException e) {
            return null;
        }
    }


    /**
     * Advance to the next page and confirm the row set changed. The
     * page-status text only carries "de N" (the current-page number is
     * in a separate input we don't read), so a static-text comparison
     * is useless. Read the first row's cédula before/after instead —
     * if the click actually advanced, the new page's first cédula
     * differs from the old one.
     */
    private boolean gotoNextPage(Page page) {
        Locator next = page.locator(PAGE_NEXT).first();
        if (next.count() == 0) return false;
        if (isDisabled(next)) return false;
        String beforeCedula = firstRowCedula(page);
        try {
            next.click();
        } catch (RuntimeException e) {
            return false;
        }
        page.waitForTimeout(500);  // SPA re-render
        String afterCedula = firstRowCedula(page);
        return !afterCedula.isEmpty() && !afterCedula.equals(beforeCedula);
    }

    private static String firstRowCedula(Page page) {
        Locator inputs = page.locator("input[name=\"salario\"]");
        if (inputs.count() == 0) return "";
        String rowText;
        try {
            rowText = (String) inputs.first().evaluate(
                    "(el) => { let p = el.parentElement; "
                            + "while (p && !/\\d{9,12}/.test(p.textContent || '')) "
                            + "p = p.parentElement; "
                            + "return p ? (p.textContent || '') : ''; }");
        } catch (RuntimeException e) {
            return "";
        }
        if (rowText == null) return "";
        Matcher m = CEDULA_PATTERN.matcher(rowText.replaceAll("\\s+", " "));
        return m.find() ? m.group() : "";
    }

    private void gotoFirstPage(Page page) {
        Locator prev = page.locator(PAGE_PREV).first();
        if (prev.count() == 0) return;
        // Click prev until disabled — there's no first-page button on the
        // recording. Defensive cap so a broken disabled-state can't loop.
        for (int i = 0; i < 50; i++) {
            if (isDisabled(prev)) return;
            try {
                prev.click();
            } catch (RuntimeException e) {
                return;
            }
            page.waitForTimeout(300);
        }
    }

    private static boolean isDisabled(Locator locator) {
        // Locator.isDisabled() honors the native HTML disabled attribute
        // and aria-disabled="true". INS's paginator buttons use the bare
        // <button disabled> form, which neither class-based nor
        // aria-disabled-only checks would catch — without this, click()
        // tries to actuate the disabled button and burns its 30s
        // actionability timeout per call.
        try {
            if (locator.isDisabled()) return true;
        } catch (RuntimeException ignored) {
            // Fall through to attribute checks below.
        }
        String cls = locator.getAttribute("class");
        if (cls != null && (cls.contains("disabled") || cls.contains("ui-state-disabled"))) {
            return true;
        }
        String aria = locator.getAttribute("aria-disabled");
        return "true".equalsIgnoreCase(aria);
    }

    private void saveRow(Page page, PortalRow row, BigDecimal salary,
                         Map<String, String> bindings, RunManifest manifest) {
        if (dryRun) {
            manifest.step("save-DRYRUN",
                    "id=" + row.identification() + " wouldSave=" + salary.toPlainString());
            return;
        }

        // With page-size bumped to 200 in beforeStepsImpl, every row of a
        // realistic NeoProc-managed planilla is rendered on a single page.
        // Anchor directly on the accordion-item whose subtree contains
        // the canonical cédula — INS reshuffles row order after each
        // auto-save but {@code :has-text} still finds the row regardless
        // of position, and there's no pagination to navigate. The prior
        // search-box approach had to fight the magnifier-icon submit
        // affordance (a real button we couldn't click cleanly) and the
        // X-clear button that mounts inside the search wrapper; both
        // sidestepped by the page-size bump.
        Locator targetRow = page.locator(
                "div.accordion-item:has(input[name=\"salario\"])"
                        + ":has-text(\"" + row.identification() + "\")");
        Locator input = targetRow.locator("input[name=\"salario\"]").first();
        input.waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(10_000));
        input.scrollIntoViewIfNeeded();
        input.click();
        // Real keystrokes (Ctrl+A / Delete / pressSequentially) so INS's
        // SPA input-event listeners actually fire — fill() with element
        // .value= bypasses them, which is what stranded prior runs at
        // 19:49 with empty inputs. Tab to commit; auto-save fires on blur.
        input.press("Control+A");
        input.press("Delete");
        input.pressSequentially(formatSalaryForInput(salary),
                new Locator.PressSequentiallyOptions().setDelay(40));
        input.press("Tab");
        page.waitForTimeout(400);                  // toast settles
        manifest.step("save-row",
                "id=" + row.identification() + " salary=" + salary.toPlainString());
    }

    /**
     * Bump the rows-per-page combobox so the entire planilla renders on
     * one page. Tries the native {@code <select>} affordance first
     * (semantically correct, handles by-value), then falls back to the
     * custom-dropdown click-and-pick pattern that some INS theme variants
     * use. Best-effort: a failure here just means saveRow falls back to
     * whatever default page size INS exposed (10 today), so the run will
     * fail mid-loop on a row that paginated off-screen — caller logs it
     * but doesn't abort because saveRow exceptions are now caught.
     */
    private void setPageSize(Page page, String size, RunManifest manifest) {
        // Strategy 1: native <select> with the desired option. Bootstrap
        // form-select renders this way, and {@code selectOption} dispatches
        // a real change event so the SPA's listener fires.
        Locator nativeSelect = page.locator(
                "select:visible:has(option[value=\"" + size + "\"])").first();
        if (nativeSelect.count() > 0) {
            try {
                nativeSelect.selectOption(size);
                page.waitForTimeout(600);
                manifest.step("page-size",
                        "set to " + size + " via native <select>");
                return;
            } catch (RuntimeException ignored) {
                // Fall through to the custom-dropdown path.
            }
        }
        // Strategy 2: custom dropdown — anchor on the visible "Mostrando"
        // label and click the trigger that follows it (a button or
        // role=combobox), then click the option whose visible text
        // matches {@code size}. Two-phase: open the popup, then pick.
        Locator trigger = page.locator(
                "xpath=//*[normalize-space(text())='Mostrando']"
                        + "/following::*[self::button or @role='combobox' "
                        + "or self::div[@aria-haspopup='listbox'] "
                        + "or self::select][1]").first();
        if (trigger.count() > 0) {
            try {
                trigger.click(new Locator.ClickOptions().setTimeout(2_000));
                page.waitForTimeout(300);
                Locator option = page.locator(
                        ":visible:text-is(\"" + size + "\")").first();
                option.click(new Locator.ClickOptions().setTimeout(2_000));
                page.waitForTimeout(600);
                manifest.step("page-size",
                        "set to " + size + " via custom dropdown");
                return;
            } catch (RuntimeException ignored) {
                // Fall through to the warning path.
            }
        }
        log.warn("page-size: could not bump rows-per-page to {} — "
                + "saveRow may fail on rows that paginate off-screen", size);
        manifest.step("page-size-skip",
                "could not set rows-per-page to " + size + "; using default");
    }

    /**
     * Format a canonical BigDecimal salary for the INS salario input.
     * Diag capture 2026-05-04T14:44 confirmed INS pads any value entered
     * to two decimals on blur (the input HTML carried {@code value="425,000.00"}
     * pre-fill, and our integer-only fill of {@code 889,985} was reformatted
     * to {@code 889,985.00} on Tab). Rounding to integer therefore drops
     * the canonical's cents and the totals reconciliation comes back off
     * by the per-row rounding error. Send 2-decimal precision so the
     * portal-reported sum matches the canonical sum byte-for-byte.
     *
     * <p>{@code DecimalFormat} with explicit US symbols guarantees
     * {@code "889,985.36"} regardless of the JVM default locale (a CRC
     * locale would otherwise produce {@code "889.985,36"}, which INS
     * does not accept).
     */
    private static String formatSalaryForInput(BigDecimal salary) {
        BigDecimal scaled = salary.setScale(2, RoundingMode.HALF_UP);
        return new java.text.DecimalFormat(
                "#,##0.00",
                new java.text.DecimalFormatSymbols(Locale.US)).format(scaled);
    }

    // --- Resumen dialog ----------------------------------------------------

    private DialogTotals openResumenAndScrape(Page page, RunManifest manifest) {
        if (dryRun) {
            // Don't even open the Resumen — keeps the planilla truly
            // untouched. Returns zero totals; the result envelope's
            // status will reflect the matched roster diff but mark
            // MISMATCH against the canonical (since portalReported = 0).
            // The operator re-runs without dryRun for the real save.
            manifest.step("resumen-DRYRUN", "skipped Continuar in dry-run mode");
            return new DialogTotals("", 0, BigDecimal.ZERO);
        }

        safeClick(page.locator(CONTINUAR_BUTTON), "Continuar");
        // Two-phase wait: first the dialog mounts (with a loader spinner),
        // then INS fetches the totals async and replaces the spinner with
        // the rendered "Total de salarios:" / "Consecutivo temporal:" /
        // etc. Anchoring on a label that only exists post-load gates the
        // scrape correctly — verified via diag.dumpResumen 2026-05-04T14:44
        // (skeletal capture, 0 bytes of text) vs 2026-05-04T15:11 (loaded,
        // 364 bytes). 30s ceiling matches the loader timeout we observed
        // in the wild + a safety margin.
        page.waitForSelector(RESUMEN_DIALOG + ":has-text(\"Total de salarios:\")",
                new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(30_000));

        // Single innerText scrape + label-anchored regex extracts. The
        // Resumen dialog is small (6 labels + values + 2 buttons), so
        // pulling the whole text is cheap and resilient to layout tweaks.
        String text = page.locator(RESUMEN_DIALOG).first().innerText();
        log.info("resumen dialog text:\n{}", text);
        manifest.step("resumen-scrape", "text len=" + text.length());
        // Stash the raw dialog text for PortalConfirmation.rawText so the
        // HITL reviewer sees exactly what INS displayed (consecutivo /
        // trabajadores / total / promedio / period dates) without having
        // to re-render the artifact screenshot.
        resumenRawText = text;

        return new DialogTotals(
                extractAfter(text, "Consecutivo temporal:"),
                parseIntOrZero(extractAfter(text, "Trabajadores reportados:")),
                parseInsMoney(extractAfter(text, "Total de salarios:")));
    }

    /**
     * Pull the value following a "Label:" line in the dialog innerText.
     * INS renders the Resumen with each label and its value as separate
     * block-level elements, so innerText interleaves them with newlines:
     * <pre>
     * Total de salarios:
     * ₡ 18 509 691,90
     * Promedio de salarios:
     * ...
     * </pre>
     * Skip leading whitespace/newlines after the label so we read the
     * value line, not the empty zero-length slice between the label-end
     * and its trailing {@code \n}. Verified via diag.dumpResumen
     * 2026-05-04T15:11 dump of the full Resumen text.
     */
    private static String extractAfter(String text, String label) {
        int idx = text.indexOf(label);
        if (idx < 0) return "";
        int start = idx + label.length();
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        int end = text.indexOf('\n', start);
        return (end < 0 ? text.substring(start) : text.substring(start, end)).trim();
    }

    private static int parseIntOrZero(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

    /**
     * Parse the European-format money string INS displays, e.g.
     * "₡ 17 989 706,90". Strategy: strip everything that isn't a digit /
     * comma / dot / minus, then decide which separator is the decimal:
     * <ul>
     *   <li>If the last comma is right of the last dot: comma is the
     *       decimal — strip dots (thousand seps), replace comma with dot.</li>
     *   <li>If the last dot is right of the last comma: dot is the decimal
     *       — strip commas (thousand seps).</li>
     *   <li>If only one separator type appears: that one is the decimal.</li>
     * </ul>
     * Handles the two main inputs we see today: "17 989 706,90" (dialog
     * total) and "1,127,500" / "1,127,500.00" (input value).
     */
    private static BigDecimal parseInsMoney(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.replaceAll("[^0-9,.\\-]", "");
        if (cleaned.isEmpty()) return null;
        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');
        if (lastComma > lastDot) {
            cleaned = cleaned.replace(".", "").replace(',', '.');
        } else if (lastDot >= 0) {
            cleaned = cleaned.replace(",", "");
        } else {
            // No separators at all — pure integer string.
        }
        try {
            return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    // --- Canonical input loading ------------------------------------------

    private List<Canonical> loadCanonical(Map<String, String> bindings) {
        String submitPath = bindings.get("params.source.submitRequest");
        if (submitPath == null || submitPath.isBlank()) return List.of();

        PayrollSubmitRequest env = EnvelopeIo.read(Path.of(submitPath), PayrollSubmitRequest.class);
        sourceCaptureEnvelopeId = env.task() != null ? env.task().sourceCaptureEnvelopeId() : null;
        SubmitRequestBody body = decodeBody(env);
        List<Canonical> out = new ArrayList<>(body.employees().size());
        for (SubmitRequestBody.SubmitEmployee e : body.employees()) {
            out.add(new Canonical(e.id(), e.displayName(), e.salary(), e.attributes()));
        }
        return out;
    }

    private static SubmitRequestBody decodeBody(PayrollSubmitRequest env) {
        if (env.encryption() == null) {
            return EnvelopeIo.MAPPER.convertValue(env.request(), SubmitRequestBody.class);
        }
        if (!(env.request() instanceof String ciphertext)) {
            throw new IllegalStateException(
                    "submit-request marked encrypted but request field is not a ciphertext string");
        }
        EnvelopeCipher cipher = EnvelopeIo.defaultCipher();
        return EnvelopeIo.decryptBody(ciphertext, env.encryption(), cipher, SubmitRequestBody.class);
    }

    // --- Matching, review, helpers ----------------------------------------

    private Optional<EmployeeMatcher.MatchResult> findMatchingRow(Canonical c, List<PortalRow> rows) {
        List<String> ids = new ArrayList<>(rows.size());
        List<String> names = new ArrayList<>(rows.size());
        for (PortalRow r : rows) {
            ids.add(r.identification());
            names.add(r.name());
        }
        Optional<EmployeeMatcher.MatchResult> result = c.name() != null
                ? EmployeeMatcher.matchWithDrift(c.id(), c.name(), ids, names)
                : EmployeeMatcher.matchById(c.id(), ids)
                        .map(idx -> new EmployeeMatcher.MatchResult(idx, true));
        if (result.isEmpty()) {
            Optional<Integer> byIdOnly = EmployeeMatcher.matchById(c.id(), ids);
            if (byIdOnly.isEmpty()) {
                log.info("match-debug canonical-id-not-found canonicalId='{}' normalizedCanonical='{}' portalIds={}",
                        c.id(), EmployeeMatcher.normalizeId(c.id()), ids);
            } else {
                int idx = byIdOnly.get();
                log.info("match-debug duplicate-cedula-with-name-mismatch canonicalId='{}' canonicalName='{}' firstPortalName='{}'",
                        c.id(), c.name(), names.get(idx));
            }
        }
        return result;
    }

    private static String textOrEmpty(Locator loc) {
        if (loc.count() == 0) return "";
        try {
            return loc.innerText().trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Click guarded against {@link #FORBIDDEN_BUTTON_LABELS}. Reads the
     * locator's current visible text and refuses if it matches a forbidden
     * label after whitespace+case normalization.
     */
    private static void safeClick(Locator locator, String expectedLabel) {
        String visible = "";
        try {
            visible = locator.first().innerText().trim();
        } catch (RuntimeException ignored) {
            // Locator may not expose innerText cleanly; fall through.
            // The denylist's value is catching obvious refactor mistakes,
            // not adversarial input.
        }
        String normalized = visible.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_BUTTON_LABELS) {
            if (normalized.equals(forbidden)) {
                throw new IllegalStateException(
                        "Refusing to click forbidden button: visible='" + visible
                                + "' expected='" + expectedLabel + "'. "
                                + "ins-rt-virtual is save-only; Presentar planilla is owned "
                                + "by the HITL final-submit user task, never the agent.");
            }
        }
        locator.click();
    }

    private static SubmitResultBody.Review buildReview(
            String status, BigDecimal canonical, BigDecimal portal, RosterDiff diff,
            List<SubmitResultBody.Signal> nameDrifts,
            List<SubmitResultBody.Signal> fillFailures) {
        if (!EnvelopeStatus.MISMATCH.equals(status) && !EnvelopeStatus.PARTIAL.equals(status)) {
            return null;
        }
        List<SubmitResultBody.Signal> signals = new ArrayList<>();
        if (canonical.compareTo(portal) != 0) {
            signals.add(new SubmitResultBody.Signal(
                    "TOTAL_GAP", canonical, portal, portal.subtract(canonical),
                    null, null, null, null));
        }
        diff.missingFromPortal().forEach(m -> signals.add(new SubmitResultBody.Signal(
                "MISSING_FROM_PORTAL", null, null, null,
                m.id(), m.name(), m.expectedSalary(), null)));
        diff.missingFromPayroll().forEach(m -> signals.add(new SubmitResultBody.Signal(
                "MISSING_FROM_PAYROLL", null, null, null,
                m.id(), m.displayName(), null, m.lastKnownSalary())));
        signals.addAll(nameDrifts);
        signals.addAll(fillFailures);

        StringBuilder summary = new StringBuilder();
        if (canonical.compareTo(portal) != 0) {
            BigDecimal gap = portal.subtract(canonical).abs();
            String direction = portal.compareTo(canonical) > 0
                    ? "Portal exceeds expected" : "Expected exceeds portal";
            summary.append(direction).append(" by ").append(gap.toPlainString()).append(" CRC");
        }
        if (!diff.isEmpty()) {
            if (!summary.isEmpty()) summary.append("; ");
            int mfp = diff.missingFromPortal().size();
            int mfpay = diff.missingFromPayroll().size();
            if (mfp > 0) summary.append(mfp).append(" employee(s) missing from portal");
            if (mfpay > 0) {
                if (mfp > 0) summary.append(", ");
                summary.append(mfpay).append(" missing from payroll");
            }
        }
        if (!nameDrifts.isEmpty()) {
            if (!summary.isEmpty()) summary.append("; ");
            summary.append(nameDrifts.size())
                    .append(" name disagreement(s) — cédula matched, names differ (HITL review)");
        }
        if (!fillFailures.isEmpty()) {
            if (!summary.isEmpty()) summary.append("; ");
            summary.append(fillFailures.size())
                    .append(" row(s) failed to save mid-loop — HITL must verify and resubmit");
        }
        return new SubmitResultBody.Review(
                severityFor(status), summary.toString(), signals,
                List.of("FINAL_SUBMIT", "RESUBMIT", "ESCALATE"));
    }

    private static String severityFor(String status) {
        if (EnvelopeStatus.MISMATCH.equals(status)) return "ERROR";
        return "WARN";
    }

    /** Internal canonical employee shape, matches MockPayrollAdapter's. */
    private record Canonical(String id, String name, BigDecimal salary, Map<String, String> attributes) {
        Canonical {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    /**
     * One row of the planilla edit table, scraped pre-fill.
     * {@code pageIndex} is captured so the apply loop can navigate back
     * to the right page before re-locating the row by cédula text.
     */
    private record PortalRow(int pageIndex,
                             String identification,
                             String name,
                             BigDecimal currentSalary) {}

    /** Scraped values from the post-Continuar Resumen de planilla dialog. */
    private record DialogTotals(String consecutivoTemporal,
                                int trabajadoresReportados,
                                BigDecimal totalSalarios) {}
}
