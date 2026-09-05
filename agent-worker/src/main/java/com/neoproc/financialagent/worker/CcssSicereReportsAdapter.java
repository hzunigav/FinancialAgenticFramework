package com.neoproc.financialagent.worker;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.LoadState;
import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.worker.portal.PortalDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Archives the CCSS Sicere reports that are only available while the planilla
 * for a period is still <em>preelaborada</em> — the submittal window. Runs as
 * its own portal run after a payroll submit is confirmed, so a report failure
 * can never flip an already-successful submit.
 *
 * <h2>Why this is not descriptor-driven</h2>
 * Three portal behaviours make YAML steps unworkable, all established by
 * driving the real portal:
 *
 * <ul>
 *   <li><b>The file never arrives as a download.</b> Every report — whatever
 *       the format — is served from one parameterless endpoint,
 *       {@code /autogestion/obtenerReporteOV?}, with
 *       {@code Content-Disposition: inline}. Chromium renders that in its PDF
 *       viewer, so no download event fires, and the navigation response
 *       readable from Playwright is a ~345-byte {@code <embed>} wrapper rather
 *       than the document. The bytes are only obtainable by intercepting the
 *       request ({@link #captureDocument}).</li>
 *   <li><b>Rejections look like successes.</b> A report the portal declines —
 *       most often a period with no preelaborada planilla — still answers
 *       HTTP 200. The refusal exists only as text in the {@code formAPL:messages}
 *       panel. Waiting on the document alone reads a business rejection as a
 *       timeout.</li>
 *   <li><b>Report identity is server-side session state.</b> The endpoint takes
 *       no parameters; which report it returns depends on the postback that
 *       preceded it. Reports must therefore be pulled strictly serially, and
 *       the document request must not be replayed.</li>
 * </ul>
 *
 * <p>Files land in {@code <runDir>/reports/} and ride to S3 with the rest of
 * the run directory, so Praxis reaches them by stripping {@code /manifest.json}
 * from {@code audit.manifestPath} (see {@code docs/ArtifactStorage.md}).
 */
final class CcssSicereReportsAdapter extends BaseAdapter {

    private static final Logger log = LoggerFactory.getLogger(CcssSicereReportsAdapter.class);

    private static final String AUTOGESTION = "https://aissfa.ccss.sa.cr/autogestion";
    private static final DateTimeFormatter MM_YYYY = DateTimeFormatter.ofPattern("MM/yyyy");

    /** How long one report may take to generate before it counts as lost. */
    private static final int DOCUMENT_TIMEOUT_MS = 45_000;
    /** Matches ccss-sicere.yaml minIntervalSeconds; CCSS throttles hard. */
    private static final long BETWEEN_REPORTS_MS = 2_000;

    /**
     * A report and how to reach it.
     *
     * @param navPath report page, relative to /autogestion; reachable directly,
     *                confirmed against the live portal for all five
     * @param trigger control that produces the document — note Exoneración uses
     *                {@code btnBuscar} while the others use {@code btnImprimir},
     *                despite all being labelled "Imprimir"
     * @param hasForm whether the page carries the period/format inputs. Detalle
     *                de Cuotas is produced straight off the menu with no
     *                parameters, so it has neither and is PDF-only.
     */
    private record ReportSpec(String key, String fileType, String navPath,
                              String trigger, boolean hasForm) {}

    private static final List<ReportSpec> REPORTS = List.of(
            new ReportSpec("detalle-cuotas", "DetalleCuotas", "/factura/listado/index.xhtml",
                    "li[id=\"menuform:mnu_reportes\"] > a", false),
            new ReportSpec("detalle-facturacion", "DetalleFacturacion",
                    "/factura/verFacturas/reporteDetalleFacturacion.xhtml",
                    "button[id=\"formAPL:btnImprimir\"]", true),
            new ReportSpec("planilla", "Planilla", "/planilla/imprimir/reportePlanilla.xhtml",
                    "button[id=\"formAPL:btnImprimir\"]", true),
            new ReportSpec("exoneracion-trabajadores", "ExoneracionTrabajadores",
                    "/factura/exoneraciones/index.xhtml?tipReporteExo=Trabajadores",
                    "button[id=\"formAPL:btnBuscar\"]", true),
            new ReportSpec("exoneracion-patrono", "ExoneracionPatrono",
                    "/factura/exoneraciones/index.xhtml?tipReporteExo=Patronos",
                    "button[id=\"formAPL:btnBuscar\"]", true));

    /** Longest company segment kept in a filename; full names run to 50+ chars. */
    private static final int COMPANY_TOKEN_MAX = 40;

    /** Set before each click so the interceptor can name the file it captures. */
    private String currentDocName;
    /** Company token for filenames, resolved once from the portal itself. */
    private String companyToken;
    /** Files written during this run, newest last. */
    private final List<String> captured = new ArrayList<>();
    private Path reportsDir;

    @Override
    public void beforeSteps(PortalDescriptor descriptor,
                            Page page,
                            Map<String, String> bindings,
                            Map<String, List<Map<String, String>>> listBindings,
                            PortalCredentials credentials,
                            RunManifest manifest) {
        Path runDir = Path.of(require(bindings, "runtime.runDir"));
        reportsDir = runDir.resolve("reports");
        try {
            Files.createDirectories(reportsDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create reports dir: " + reportsDir, e);
        }

        String period = resolvePeriod(bindings);
        List<String> formats = resolveFormats(bindings);
        manifest.step("reports", "period=" + period + " formats=" + formats);

        // One interceptor for the whole run: every report, every format, comes
        // back from this endpoint.
        page.context().route("**/obtenerReporteOV*", this::captureDocument);

        Map<String, String> outcomes = new LinkedHashMap<>();
        for (ReportSpec spec : REPORTS) {
            for (String format : spec.hasForm() ? formats : List.of("pdf")) {
                String label = spec.key() + "-" + format;
                String outcome = runReport(page, spec, format, period);
                outcomes.put(label, outcome);
                manifest.step("report:" + label, outcome);
                log.info("report {} -> {}", label, outcome);
                sleep(BETWEEN_REPORTS_MS);
            }
        }
        bindings.put("reports.outcomes", outcomes.toString());
        bindings.put("reports.capturedCount", String.valueOf(captured.size()));
        writeIndex(period, outcomes);
    }

    /**
     * Drives one report to a file or a stated reason. Never throws: a single
     * unavailable report must not abort the archival of the others, so every
     * failure is returned as text and surfaced through the run status.
     */
    private String runReport(Page page, ReportSpec spec, String format, String period) {
        try {
            page.navigate(AUTOGESTION + spec.navPath());
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        } catch (RuntimeException e) {
            return "NAV_ERROR: " + brief(e);
        }
        resolveCompanyToken(page);

        if (spec.hasForm()) {
            try {
                // Format first: changing it re-renders formAPL:pnlDatos, the
                // panel holding the period input, so a period set beforehand is
                // wiped and the portal then rejects the request as empty.
                String applied = setFormat(page, format);
                if (!format.equals(applied)) {
                    return "FORMAT_ERROR: form carries " + applied;
                }
                setPeriod(page, period);
                String staged = periodValue(page);
                if (!period.equals(staged)) {
                    return "PERIOD_ERROR: field holds '" + staged + "'";
                }
            } catch (RuntimeException e) {
                return "FORM_ERROR: " + brief(e);
            }
        }

        currentDocName = documentBaseName(spec, period);
        int before = captured.size();
        try {
            clickTrigger(page, spec);
        } catch (RuntimeException e) {
            currentDocName = null;
            return "CLICK_ERROR: " + brief(e);
        }

        try {
            long deadline = System.currentTimeMillis() + DOCUMENT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (captured.size() > before) {
                    return "CAPTURED: " + captured.get(captured.size() - 1);
                }
                String error = readPortalError(page);
                if (!error.isBlank()) {
                    return "PORTAL_ERROR: " + error;
                }
                page.waitForTimeout(250);
            }
            return "TIMEOUT: no document in " + (DOCUMENT_TIMEOUT_MS / 1000) + "s";
        } finally {
            currentDocName = null;
        }
    }

    /**
     * Takes the report bytes off the intercepted request.
     *
     * <p>{@code route.fetch()} performs the paused request itself, so the
     * server sees exactly one call — which matters because the endpoint returns
     * whatever the preceding postback staged in the session. The request is
     * then aborted rather than replayed: the bytes are already saved, and
     * fulfilling it only makes the browser render or re-download a second copy.
     */
    private void captureDocument(Route route) {
        String name = currentDocName;
        if (name == null) {
            // A report we did not initiate (e.g. a stray retry). Let it pass
            // rather than swallow it silently.
            route.resume();
            return;
        }
        APIResponse response;
        try {
            response = route.fetch();
        } catch (RuntimeException e) {
            log.warn("report intercept failed for {}: {}", route.request().url(), brief(e));
            route.resume();
            return;
        }
        try {
            byte[] bytes = response.body();
            String filename = name + extensionFor(response.headers());
            Files.write(reportsDir.resolve(filename), bytes);
            String summary = "%s (%,d bytes, %s)".formatted(filename, bytes.length, sniff(bytes));
            captured.add(summary);
            log.info("report captured {}", summary);
        } catch (IOException | RuntimeException e) {
            log.warn("report write failed for {}: {}", name, brief(e));
        }
        try {
            route.abort();
        } catch (RuntimeException alreadyHandled) {
            // The browser has moved on; the file is already on disk.
        }
    }

    @Override
    public String captureToManifest(Map<String, String> scraped,
                                    List<Map<String, String>> scrapedRows,
                                    Map<String, String> bindings,
                                    PortalCredentials credentials,
                                    RunManifest manifest) {
        int expected = expectedDocumentCount(resolveFormats(bindings));
        int actual = captured.size();
        if (actual == 0) {
            manifest.error = "no reports captured";
            return "FAILED";
        }
        // A partial archive is still worth keeping — the submittal window may
        // have closed for one report while the rest are valid — but it must not
        // read as a clean run.
        return actual < expected ? "PARTIAL" : "SUCCESS";
    }

    private static int expectedDocumentCount(List<String> formats) {
        int count = 0;
        for (ReportSpec spec : REPORTS) {
            count += spec.hasForm() ? formats.size() : 1;
        }
        return count;
    }

    // --- portal interaction -------------------------------------------------

    /**
     * Selects the output format. The visible PrimeFaces widget is preferred,
     * but the value that actually gets posted is the native select it wraps
     * ({@code formAPL:tipFormato_input}), so that is both the fallback and the
     * thing asserted — the styled label is cosmetic.
     */
    private static String setFormat(Page page, String format) {
        if (page.locator("select[id=\"formAPL:tipFormato_input\"]").count() == 0) {
            return format;
        }
        try {
            page.locator("div[id=\"formAPL:tipFormato\"]").first()
                    .click(new Locator.ClickOptions().setTimeout(3_000));
            String wanted = switch (format) {
                case "xls" -> "Formato Excel";
                case "txt" -> "Formato .txt";
                default -> "Formato .pdf";
            };
            page.locator("ul[id=\"formAPL:tipFormato_items\"] li[data-label=\"" + wanted + "\"]")
                    .first().click(new Locator.ClickOptions().setTimeout(3_000));
        } catch (RuntimeException widgetUnavailable) {
            // Panel is built lazily and appended to the body; fall through.
        }
        if (!format.equals(currentFormat(page))) {
            page.evaluate("fmt => { const s = document.getElementById('formAPL:tipFormato_input');"
                    + " s.value = fmt; s.dispatchEvent(new Event('change', { bubbles: true })); }",
                    format);
        }
        return currentFormat(page);
    }

    private static String currentFormat(Page page) {
        Object value = page.evaluate("() => { const s = document.getElementById('formAPL:tipFormato_input');"
                + " return s ? s.value : ''; }");
        return value == null ? "" : value.toString();
    }

    /**
     * Fills the required Período (MM/yyyy). The overlay is hidden rather than
     * dismissed with Escape, which the jQuery datepicker treats as "revert" and
     * which would clear the field again.
     */
    private static void setPeriod(Page page, String period) {
        Locator input = page.locator("input[id=\"formAPL:fecDesde_input\"]");
        if (input.count() == 0) {
            return;
        }
        input.fill(period);
        if (!period.equals(periodValue(page))) {
            page.evaluate("val => { const el = document.getElementById('formAPL:fecDesde_input');"
                    + " el.value = val;"
                    + " el.dispatchEvent(new Event('input', { bubbles: true }));"
                    + " el.dispatchEvent(new Event('change', { bubbles: true })); }", period);
        }
        page.evaluate("() => { const dp = document.getElementById('ui-datepicker-div');"
                + " if (dp) { dp.style.setProperty('display', 'none', 'important'); } }");
    }

    private static String periodValue(Page page) {
        Object value = page.evaluate("() => { const el = document.getElementById('formAPL:fecDesde_input');"
                + " return el ? el.value : ''; }");
        return value == null ? "" : value.toString().trim();
    }

    private static void clickTrigger(Page page, ReportSpec spec) {
        Locator trigger = page.locator(spec.trigger()).first();
        try {
            trigger.click(new Locator.ClickOptions().setTimeout(5_000));
        } catch (RuntimeException notClickable) {
            // Menu entries live in a collapsible sidebar and are present but
            // not visible; running the element's own handler is the same code
            // path the click would take.
            String onclick = (String) page.evalOnSelector(
                    spec.trigger(), "el => el.getAttribute('onclick')");
            if (onclick == null || onclick.isBlank()) {
                throw notClickable;
            }
            page.evaluate("() => { " + onclick + " }");
        }
    }

    /** The JSF messages panel is the only place a refusal appears. */
    private static String readPortalError(Page page) {
        try {
            Locator errors = page.locator(
                    "[id=\"formAPL:messages\"] .ui-messages-error-summary, "
                    + "[id=\"formAPL:messages\"] .ui-messages-fatal-summary");
            return errors.count() == 0 ? "" : errors.first().innerText().trim();
        } catch (RuntimeException pageBusy) {
            return "";
        }
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Period the reports cover, as MM/yyyy. Defaults to the prior month: this
     * run follows a confirmed submit, and the planilla just submitted covers
     * the month that has ended, not the current one.
     */
    private static String resolvePeriod(Map<String, String> bindings) {
        String explicit = bindings.getOrDefault("params.period", "");
        return explicit.isBlank() ? YearMonth.now().minusMonths(1).format(MM_YYYY) : explicit.trim();
    }

    private static List<String> resolveFormats(Map<String, String> bindings) {
        String raw = bindings.getOrDefault("params.formats", "pdf,xls");
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Builds the archive filename as {@code <FileType><CompanyName><Month><Year>},
     * e.g. {@code PlanillaNeoprocSociedadAnonima082026.pdf}. The extension comes
     * from what the portal actually sent, so the same base name distinguishes
     * the PDF and the Excel of one report.
     *
     * <p>The period drives Month/Year, not the clock: a run kicked off just
     * after month-end is archiving the month that closed, and naming it with
     * today's date would file it under the wrong month.
     */
    private String documentBaseName(ReportSpec spec, String period) {
        return spec.fileType() + company() + periodSuffix(period);
    }

    /**
     * Renders MM/yyyy as MMyyyy. A period that isn't in that shape yields
     * {@code 000000} rather than a truncated guess, so a malformed period is
     * obvious in the filename instead of silently filing under a real month.
     */
    static String periodSuffix(String period) {
        String trimmed = period == null ? "" : period.trim();
        return trimmed.matches("\\d{2}/\\d{4}")
                ? trimmed.substring(0, 2) + trimmed.substring(3)
                : "000000";
    }

    private String company() {
        return companyToken == null || companyToken.isBlank() ? "UnknownCompany" : companyToken;
    }

    /**
     * Reads the employer name off the portal once per run.
     *
     * <p>Anchored on the "Razón Social" label's text rather than its id: JSF
     * generates those ids ({@code formAPL:j_idt116}) from component ordering,
     * so they shift whenever the page layout is edited. The name is taken from
     * the portal rather than a parameter so the filename always matches the
     * account the documents actually came from — a mismatch there would file a
     * client's reports under another client's name.
     */
    private void resolveCompanyToken(Page page) {
        if (companyToken != null) {
            return;
        }
        try {
            Object raw = page.evaluate("""
                    () => {
                      const labels = Array.from(document.querySelectorAll('label, span, div'));
                      const i = labels.findIndex(el =>
                        /Raz[oó]n\\s*Social/i.test((el.textContent || '').trim()));
                      if (i < 0) return '';
                      for (let j = i + 1; j < Math.min(i + 6, labels.length); j++) {
                        const t = (labels[j].textContent || '').trim();
                        if (t && !/Raz[oó]n\\s*Social/i.test(t) && t.length < 120) return t;
                      }
                      return '';
                    }""");
            String name = raw == null ? "" : raw.toString().trim();
            if (!name.isBlank()) {
                companyToken = toToken(name);
                log.info("company resolved from portal: {} -> {}", name, companyToken);
            }
        } catch (RuntimeException e) {
            log.warn("could not read company name: {}", brief(e));
        }
    }

    /**
     * Turns "NEOPROC SOCIEDAD ANONIMA" into "NeoprocSociedadAnonima": accents
     * folded, punctuation dropped, words capitalised, length capped so a long
     * corporate name cannot dominate the filename.
     */
    static String toToken(String rawName) {
        String ascii = java.text.Normalizer.normalize(rawName, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        StringBuilder sb = new StringBuilder();
        for (String word : ascii.split("[^A-Za-z0-9]+")) {
            if (word.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(word.charAt(0)))
              .append(word.substring(1).toLowerCase(Locale.ROOT));
            if (sb.length() >= COMPANY_TOKEN_MAX) {
                break;
            }
        }
        String token = sb.toString();
        return token.length() > COMPANY_TOKEN_MAX ? token.substring(0, COMPANY_TOKEN_MAX) : token;
    }

    /** Trusts the portal's own filename, then the content type. */
    private static String extensionFor(Map<String, String> headers) {
        String disposition = headers.getOrDefault("content-disposition", "");
        var matcher = java.util.regex.Pattern
                .compile("filename\\*?=\"?([^\";]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(disposition);
        if (matcher.find()) {
            String filename = matcher.group(1).trim();
            int dot = filename.lastIndexOf('.');
            if (dot > 0) {
                return filename.substring(dot);
            }
        }
        String contentType = headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
        if (contentType.contains("pdf")) {
            return ".pdf";
        }
        if (contentType.contains("excel") || contentType.contains("spreadsheet")) {
            return ".xls";
        }
        return contentType.contains("text/plain") ? ".txt" : ".bin";
    }

    /**
     * Reports what actually landed. This portal serves Excel-flavoured HTML as
     * {@code .xls}, and previously served a PDF-viewer wrapper where a PDF was
     * expected, so the extension proves nothing — the magic bytes do.
     */
    private static String sniff(byte[] bytes) {
        if (bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
            return "PDF";
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF) {
            return "XLS (OLE2)";
        }
        if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') {
            return "ZIP/XLSX";
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.UTF_8)
                .trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
            return head.contains("urn:schemas-microsoft-com:office") ? "HTML (Excel-flavoured)" : "HTML";
        }
        return "unknown";
    }

    /** Human-readable inventory beside the files, for whoever opens the bundle. */
    private void writeIndex(String period, Map<String, String> outcomes) {
        StringBuilder sb = new StringBuilder("CCSS Sicere report archive\n");
        sb.append("period: ").append(period).append("\n\n");
        outcomes.forEach((label, outcome) -> sb.append(label).append(": ").append(outcome).append('\n'));
        try {
            Files.writeString(reportsDir.resolve("index.txt"), sb.toString());
        } catch (IOException e) {
            log.warn("could not write report index: {}", e.getMessage());
        }
    }

    private static String brief(Exception e) {
        String message = String.valueOf(e.getMessage()).replaceAll("\\s+", " ").trim();
        return message.length() > 160 ? message.substring(0, 160) + "..." : message;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
