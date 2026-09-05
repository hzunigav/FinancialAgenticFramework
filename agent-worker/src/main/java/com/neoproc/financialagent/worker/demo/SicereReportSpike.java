package com.neoproc.financialagent.worker.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * SICERE report-capture spike — NOT production. Opens a headed Chromium you
 * drive by hand against CCSS Sicere so the post-confirmation report bot can be
 * designed from real DOM and real network traffic, rather than guesswork.
 *
 * <p>You log in and navigate to each report yourself; at every stop you type a
 * short label and the spike writes a {@code NN-label.png} / {@code .html} /
 * {@code .txt} triple. Every PDF the portal hands over is saved automatically,
 * whether the portal pushes it as an attachment or renders it inline.
 *
 * <h2>How the PDF problem is solved</h2>
 * Three layers, because JSF/PrimeFaces report screens deliver PDFs in at least
 * three different ways and we don't yet know which one SICERE uses:
 * <ol>
 *   <li><b>Chromium profile pref</b> — the persistent profile is seeded with
 *       {@code plugins.always_open_pdf_externally=true}, so a PDF that would
 *       normally render inside Chromium's viewer (no download event, nothing
 *       on disk) is instead handed to the download machinery. This turns the
 *       hard case into the easy one.</li>
 *   <li><b>Download events</b> — {@code page.onDownload} saves each file to
 *       {@code <outDir>/downloads/dlNN-<suggested name>}, including downloads
 *       started from popups and {@code target=_blank} report links.</li>
 *   <li><b>Network log + HAR</b> — every PDF-ish response (content-type pdf or
 *       octet-stream, or a {@code Content-Disposition: attachment}) is logged
 *       with its method, URL and POST body to {@code network-pdf.log}, and the
 *       full session is recorded to {@code network.har}. If a report turns out
 *       to be a one-shot POST with a JSF view-state token, that log is what
 *       tells us the bot must click through the UI rather than hit a URL.</li>
 * </ol>
 *
 * <p>Run (headed; stdin is forwarded for the prompts):
 * <pre>
 * mvn.cmd -pl agent-worker exec:java -q \
 *   -Dexec.mainClass=com.neoproc.financialagent.worker.demo.SicereReportSpike \
 *   -Dexec.args="sicere-spike"
 * </pre>
 * Options: {@code -Dsicere.url=...} (default the {@code ccss-sicere.yaml}
 * baseUrl), {@code -Dsicere.channel=chrome} to use installed Chrome instead of
 * bundled Chromium, {@code -Dsicere.har=false} to skip HAR recording.
 *
 * <p><b>The output directory holds live session state and an unscrubbed HAR
 * (the login POST body includes the password).</b> {@code sicere-spike/} is
 * gitignored; delete it once the descriptor work is done.
 */
public final class SicereReportSpike {

    private SicereReportSpike() {}

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Lines typed at the prompt, handed over by the stdin reader thread. */
    private static final BlockingQueue<String> INPUT = new LinkedBlockingQueue<>();
    /** Sentinel meaning stdin closed — ends the run rather than spinning. */
    private static final String EOF = "\0EOF";

    /** Downloads saved so far; drained into each stop's sidecar. */
    private static final List<String> pendingDownloads = new ArrayList<>();
    /** Every download, for the closing index. */
    private static final List<String> allDownloads = new ArrayList<>();
    /** PDF-ish responses seen, for network-pdf.log. */
    private static final List<String> networkLines = new ArrayList<>();
    /**
     * The document responses themselves, for the network log only.
     *
     * <p>These are NOT a source of file bytes. SICERE serves reports
     * {@code Content-Disposition: inline}, and for a PDF Chromium answers the
     * navigation with a synthetic viewer wrapper
     * ({@code <embed type='application/pdf'>}, ~345 bytes) while the real
     * document streams to the plugin out of reach of {@code response.body()}.
     * Reading bytes here yields the wrapper, not the report.
     */
    private static final List<Response> documentResponses = new ArrayList<>();

    /** Documents captured by intercepting the request — the real bytes. */
    private static final List<String> capturedDocs = new ArrayList<>();

    private static Path downloadsDir;
    private static int downloadSeq = 0;

    public static void main(String[] args) throws IOException {
        Path outDir = Path.of(args.length > 0 ? args[0] : "sicere-spike");
        Files.createDirectories(outDir);
        downloadsDir = outDir.resolve("downloads");
        Files.createDirectories(downloadsDir);

        String startUrl = System.getProperty("sicere.url", "https://aissfa.ccss.sa.cr/afiliacion/");
        boolean recordHar = !"false".equalsIgnoreCase(System.getProperty("sicere.har", "true"));

        Path profileDir = outDir.resolve("chrome-profile");
        seedPdfPreference(profileDir);

        BrowserType.LaunchPersistentContextOptions options =
                new BrowserType.LaunchPersistentContextOptions()
                        .setHeadless(false)
                        .setViewportSize(1440, 900)
                        .setLocale("es-CR")
                        .setTimezoneId("America/Costa_Rica")
                        .setAcceptDownloads(true)
                        .setDownloadsPath(downloadsDir);
        if (recordHar) {
            options.setRecordHarPath(outDir.resolve("network.har"));
        }
        String channel = System.getProperty("sicere.channel", "");
        if (!channel.isBlank()) {
            options.setChannel(channel);
        }

        startInputReader();

        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = playwright.chromium().launchPersistentContext(profileDir, options);
            try {
                // Catches PDF-ish traffic across every page in the context,
                // popups included. Body bytes are deliberately NOT read here —
                // fetching them inside the event handler races the page's own
                // navigation; the HAR has them if we need them.
                context.onResponse(SicereReportSpike::logIfPdfish);
                // Report links that open a new tab get their own download hook.
                context.onPage(SicereReportSpike::attachPage);
                // Every report, whatever the format, comes back from this one
                // endpoint. Intercepting it is the only reliable way to get the
                // real file: inline PDFs never fire a download and their bytes
                // are unreachable once Chromium's viewer owns them.
                context.route("**/obtenerReporteOV*", SicereReportSpike::captureDocument);

                Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
                attachPage(page);
                try {
                    page.navigate(startUrl);
                } catch (RuntimeException navFailed) {
                    // A URL that resolves to a download never fires "load".
                    // Report it and carry on rather than killing the session.
                    System.out.println("initial navigate did not settle: "
                            + cell(String.valueOf(navFailed.getMessage())));
                }

                if (Boolean.getBoolean("sicere.drive")) {
                    driveAllReports(context, page, outDir);
                    writeClosingArtifacts(context, outDir);
                    return;
                }

                banner(startUrl, outDir);

                int stop = 0;
                while (true) {
                    String label = promptPumped(context,
                            "Label for the screen you're on (Enter = auto-label, 'q' = finish):");
                    if (label == null || label.trim().equalsIgnoreCase("q")) {
                        break;
                    }
                    stop++;
                    String slug = slug(label.isBlank() ? "stop" : label);
                    captureAllPages(context, outDir, stop, slug, label);
                }

                writeClosingArtifacts(context, outDir);
            } finally {
                // Flushes network.har.
                context.close();
            }
        }

        System.out.println();
        System.out.println("Done. Artifacts written to: " + outDir.toAbsolutePath());
        System.out.println("Hand back report-index.md, network-pdf.log and the *.html snapshots.");
    }

    /**
     * Writes a Chromium profile preference that stops the built-in PDF viewer
     * from swallowing report PDFs. Without it, a PDF served inline renders in
     * the viewer and never becomes a {@link Download}; with it, Chromium routes
     * the same response to the download machinery, so one code path catches
     * both delivery styles. Chromium rewrites this file on exit — seeding it
     * only matters on a fresh profile.
     */
    private static void seedPdfPreference(Path profileDir) throws IOException {
        Path prefs = profileDir.resolve("Default").resolve("Preferences");
        Files.createDirectories(prefs.getParent());
        String seed = """
                {"plugins":{"always_open_pdf_externally":true},\
                "download":{"prompt_for_download":false},\
                "profile":{"exit_type":"Normal","exited_cleanly":true}}""";
        if (!Files.exists(prefs)) {
            Files.writeString(prefs, seed);
            return;
        }
        // Chromium rewrites this file on exit, and an unclean exit (a crashed
        // run) can drop the setting entirely. Skipping the reseed whenever a
        // profile exists is how PDFs silently went back to rendering inline,
        // so patch the existing file instead of leaving it to chance.
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root;
        try {
            root = (ObjectNode) mapper.readTree(prefs.toFile());
        } catch (IOException | ClassCastException unreadable) {
            Files.writeString(prefs, seed);
            return;
        }
        ObjectNode plugins = root.has("plugins") && root.get("plugins").isObject()
                ? (ObjectNode) root.get("plugins")
                : root.putObject("plugins");
        plugins.put("always_open_pdf_externally", true);
        ObjectNode download = root.has("download") && root.get("download").isObject()
                ? (ObjectNode) root.get("download")
                : root.putObject("download");
        download.put("prompt_for_download", false);
        mapper.writeValue(prefs.toFile(), root);
    }

    private static void attachPage(Page page) {
        page.onDownload(SicereReportSpike::saveDownload);
    }

    /** Persists a download under a sequenced name and reports it at the next stop. */
    private static void saveDownload(Download download) {
        downloadSeq++;
        String suggested = download.suggestedFilename();
        // Every report is served as "reporte.pdf", so drive mode supplies the
        // report's own name to stop each download overwriting the last.
        String base = currentDownloadName == null
                ? String.format("dl%02d-%s", downloadSeq, slug(suggested))
                : slug(currentDownloadName) + extensionOf(suggested);
        Path target = downloadsDir.resolve(base);
        String stamp = LocalTime.now().format(CLOCK);
        String line;
        try {
            download.saveAs(target);
            long bytes = Files.size(target);
            line = "%s  %s  (%,d bytes)  from %s".formatted(
                    stamp, target.getFileName(), bytes, download.url());
        } catch (RuntimeException | IOException e) {
            line = "%s  FAILED  suggested=%s  url=%s  (%s)".formatted(
                    stamp, suggested, download.url(), e.getMessage());
        }
        pendingDownloads.add(line);
        allDownloads.add(line);
        System.out.println("    [PDF] " + line);
    }

    /**
     * Records the shape of any response that looks like a document handoff.
     * The method + POST body is the part that decides the bot's design: a plain
     * GET on a stable URL can be fetched directly, while a JSF POST carrying a
     * {@code javax.faces.ViewState} token can only be re-triggered by clicking
     * the real control in a live session.
     */
    private static void logIfPdfish(Response response) {
        Map<String, String> headers = response.headers();
        String contentType = headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
        String disposition = headers.getOrDefault("content-disposition", "").toLowerCase(Locale.ROOT);
        boolean interesting = contentType.contains("pdf")
                || contentType.contains("octet-stream")
                || disposition.contains("attachment");
        if (!interesting) {
            return;
        }
        documentResponses.add(response);
        String postData;
        try {
            postData = response.request().postData();
        } catch (RuntimeException e) {
            postData = null;
        }
        if (postData != null && postData.length() > 800) {
            postData = postData.substring(0, 800) + " …(truncated)";
        }
        networkLines.add("""
                %s  %s %s
                    status      : %d
                    content-type: %s
                    disposition : %s
                    post body   : %s
                """.formatted(LocalTime.now().format(CLOCK), response.request().method(),
                response.url(), response.status(), contentType,
                disposition.isBlank() ? "(none)" : disposition,
                postData == null ? "(no body — GET or navigation)" : postData));
    }

    /**
     * Snapshots every open tab. Report screens routinely open in a popup, and
     * the popup is usually the one carrying the DOM we need, so capturing only
     * a single "current" page would miss it.
     */
    private static void captureAllPages(BrowserContext context, Path outDir,
                                        int stop, String slug, String label) throws IOException {
        List<Page> open = context.pages().stream().filter(p -> !p.isClosed()).toList();
        if (open.isEmpty()) {
            System.out.println("!! no open pages to capture");
            return;
        }
        for (int i = 0; i < open.size(); i++) {
            Page page = open.get(i);
            String name = open.size() == 1
                    ? "%02d-%s".formatted(stop, slug)
                    : "%02d-%s-tab%d".formatted(stop, slug, i + 1);
            try {
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(outDir.resolve(name + ".png"))
                        .setFullPage(true));
                Files.writeString(outDir.resolve(name + ".html"), page.content());
                Files.writeString(outDir.resolve(name + ".txt"), sidecar(page, label));
                System.out.println("captured " + name + "  @ " + page.url());
            } catch (RuntimeException e) {
                System.out.println("!! capture failed for " + name + ": " + e.getMessage());
            }
        }
        pendingDownloads.clear();
    }

    /** Per-stop context: URL, title, frame tree, and the PDFs that arrived getting here. */
    private static String sidecar(Page page, String label) {
        StringBuilder sb = new StringBuilder();
        sb.append("label : ").append(label).append('\n');
        sb.append("url   : ").append(page.url()).append('\n');
        try {
            sb.append("title : ").append(page.title()).append('\n');
        } catch (RuntimeException e) {
            sb.append("title : (unavailable)\n");
        }
        sb.append("\nframes:\n");
        for (Frame frame : page.frames()) {
            sb.append("  - name=").append(frame.name().isBlank() ? "(main)" : frame.name())
              .append("  url=").append(frame.url()).append('\n');
        }
        sb.append("\ndownloads since previous stop:\n");
        if (pendingDownloads.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            pendingDownloads.forEach(d -> sb.append("  - ").append(d).append('\n'));
        }
        return sb.toString();
    }

    private static void writeClosingArtifacts(BrowserContext context, Path outDir) throws IOException {
        Files.writeString(outDir.resolve("storageState.json"), context.storageState());
        Files.writeString(outDir.resolve("network-pdf.log"),
                networkLines.isEmpty()
                        ? "No PDF-ish responses observed. Either no report was opened, or the\n"
                          + "portal delivers them in a shape this filter missed — check network.har.\n"
                        : String.join("\n", networkLines));

        StringBuilder index = new StringBuilder("# SICERE report spike\n\n");
        index.append("cookies: ").append(context.cookies().size()).append('\n');
        index.append("PDFs captured: ").append(allDownloads.size()).append('\n');
        index.append("PDF-ish responses logged: ").append(networkLines.size()).append("\n\n");
        index.append("## Downloaded files\n\n");
        if (allDownloads.isEmpty()) {
            index.append("(none — see network-pdf.log and network.har)\n");
        } else {
            allDownloads.forEach(d -> index.append("- ").append(d).append('\n'));
        }
        index.append("\n## Screens captured\n\n");
        try (var stream = Files.list(outDir)) {
            stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".html"))
                    .sorted()
                    .forEach(n -> index.append("- ").append(n).append('\n'));
        }
        Files.writeString(outDir.resolve("report-index.md"), index.toString());
    }

    // ---------------------------------------------------------------------
    // Drive mode — automated pass over every report, prototyping the adapter.
    // ---------------------------------------------------------------------

    /** Overridable so a smoke run can exercise the drive path off a dead host. */
    private static final String AUTOGESTION =
            System.getProperty("sicere.base", "https://aissfa.ccss.sa.cr/autogestion");

    /**
     * One report and how to reach it.
     *
     * @param navPath page that hosts the report form, relative to /autogestion
     * @param menuId  menu item that navigates there, used when direct
     *                navigation lands somewhere without the form
     * @param trigger control that produces the document
     * @param hasForm whether the page carries the formAPL period/format inputs;
     *                Detalle de Cuotas is produced straight off the menu with
     *                no parameters, so it has none
     */
    private record ReportSpec(String key, String label, String navPath,
                              String menuId, String trigger, boolean hasForm) {}

    private static final List<ReportSpec> REPORTS = List.of(
            new ReportSpec("detalle-cuotas", "Detalle de Cuotas",
                    "/factura/listado/index.xhtml", "menuform:mnu_reportes",
                    "li[id=\"menuform:mnu_reportes\"] > a", false),
            new ReportSpec("detalle-facturacion", "Detalle de Facturacion",
                    "/factura/verFacturas/reporteDetalleFacturacion.xhtml", "menuform:mnu_detFact",
                    "button[id=\"formAPL:btnImprimir\"]", true),
            new ReportSpec("planilla", "Planilla",
                    "/planilla/imprimir/reportePlanilla.xhtml", "menuform:mnu_repPla",
                    "button[id=\"formAPL:btnImprimir\"]", true),
            new ReportSpec("exoneracion-trabajadores", "Exoneracion Trabajadores",
                    "/factura/exoneraciones/index.xhtml?tipReporteExo=Trabajadores",
                    "menuform:mnu_exoneraTrab", "button[id=\"formAPL:btnBuscar\"]", true),
            new ReportSpec("exoneracion-patrono", "Exoneracion Patrono",
                    "/factura/exoneraciones/index.xhtml?tipReporteExo=Patronos",
                    "menuform:mnu_exoneraPat", "button[id=\"formAPL:btnBuscar\"]", true));

    /** Result of one (report, format) attempt. */
    private record Outcome(String report, String format, String period, String nav,
                           String status, String detail) {}

    /** Names the next download after the report being driven, not "reporte.pdf". */
    private static String currentDownloadName = null;

    private static void driveAllReports(BrowserContext context, Page page, Path outDir)
            throws IOException {
        List<String> periods = periodCandidates();
        List<String> formats = List.of(System.getProperty("sicere.formats", "pdf,xls").split(","));

        System.out.println();
        System.out.println("=== SICERE drive mode ===");
        System.out.println("Reports: " + REPORTS.size() + "   formats: " + formats);
        System.out.println("Periods: " + periods + "  (tried in order until one is accepted)");
        System.out.println();
        System.out.println("Log in manually in the window, land on the Oficina Virtual home,");
        String go = promptPumped(context, "then press Enter to drive every report ('q' aborts):");
        if (go == null || go.trim().equalsIgnoreCase("q")) {
            return;
        }

        List<Outcome> outcomes = new ArrayList<>();
        for (ReportSpec spec : REPORTS) {
            List<String> forThis = spec.hasForm() ? formats : List.of("pdf");
            for (String format : forThis) {
                System.out.printf("%n--- %s [%s]%n", spec.label(), format);
                Outcome outcome = driveOne(context, page, spec, format.trim(), periods, outDir);
                outcomes.add(outcome);
                System.out.printf("    %s  %s%n", outcome.status(), outcome.detail());
                closeExtraPages(context, page);
                // Matches ccss-sicere.yaml minIntervalSeconds — this portal
                // sits behind an F5 WAF and throttles hard.
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        writeDriveReport(outDir, periods, outcomes);
    }

    /**
     * Periods to try, in order. Defaults to the prior month then the current
     * one: the planilla being submitted covers the month just ended, which is
     * the value that worked by hand, while the billing reports came back for
     * the current month. Override with {@code -Dsicere.period=MM/yyyy} to pin
     * a single period.
     */
    private static List<String> periodCandidates() {
        String explicit = System.getProperty("sicere.period", "");
        if (!explicit.isBlank()) {
            return List.of(explicit.trim());
        }
        DateTimeFormatter mmYyyy = DateTimeFormatter.ofPattern("MM/yyyy");
        YearMonth now = YearMonth.now();
        return List.of(now.minusMonths(1).format(mmYyyy), now.format(mmYyyy));
    }

    /**
     * Runs one report, trying each candidate period until the portal stops
     * rejecting it. The observed periods differ per report — Detalle de
     * Facturación produced a document for the current month while Planilla
     * needed the prior one — so which period each report wants is something
     * this pass is meant to discover rather than assume.
     */
    private static Outcome driveOne(BrowserContext context, Page page, ReportSpec spec,
                                    String format, List<String> periods, Path outDir) {
        Outcome last = null;
        for (String period : spec.hasForm() ? periods : List.of("")) {
            last = driveOnce(context, page, spec, format, period, outDir);
            if (!"PORTAL_ERROR".equals(last.status())) {
                return last;
            }
            System.out.printf("    period %s rejected: %s%n", period, last.detail());
        }
        return last;
    }

    private static Outcome driveOnce(BrowserContext context, Page page, ReportSpec spec,
                                     String format, String period, Path outDir) {
        String nav;
        try {
            nav = gotoReportPage(page, spec);
        } catch (RuntimeException e) {
            return new Outcome(spec.key(), format, period, "failed", "NAV_ERROR", e.getMessage());
        }

        if (spec.hasForm()) {
            // Format FIRST. Changing it fires a JSF postback that re-renders
            // formAPL:pnlDatos — the panel holding the period input — so a
            // period set before this point is silently wiped, which the portal
            // then rejects with "Período: se necesita un valor".
            try {
                String applied = setFormat(page, format);
                if (!applied.equals(format)) {
                    return new Outcome(spec.key(), format, period, nav, "FORMAT_ERROR",
                            "form still carries " + applied);
                }
            } catch (RuntimeException e) {
                return new Outcome(spec.key(), format, period, nav, "FORMAT_ERROR", e.getMessage());
            }
            setSectorIfEmpty(page);
            if (!period.isBlank()) {
                try {
                    setPeriod(page, period);
                    String staged = periodValue(page);
                    if (!period.equals(staged)) {
                        return new Outcome(spec.key(), format, period, nav, "PERIOD_ERROR",
                                "field holds '" + staged + "' after fill");
                    }
                } catch (RuntimeException e) {
                    return new Outcome(spec.key(), format, period, nav, "PERIOD_ERROR", e.getMessage());
                }
            }
        }

        String baseName = spec.key() + "-" + format;
        currentDownloadName = baseName;
        int downloadsBefore = downloadSeq;
        int docsBefore = capturedDocs.size();
        try {
            clickTrigger(page, spec);
        } catch (RuntimeException e) {
            currentDownloadName = null;
            return new Outcome(spec.key(), format, period, nav, "CLICK_ERROR", e.getMessage());
        }

        // Race the document against a rejection. A rejected request still
        // answers HTTP 200 with nothing but a message panel, so waiting on the
        // document alone would read a business error as a timeout.
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            // Interception wins: it holds the true bytes regardless of whether
            // the browser went on to download the file or render it inline.
            if (capturedDocs.size() > docsBefore) {
                currentDownloadName = null;
                return new Outcome(spec.key(), format, period, nav, "CAPTURED",
                        capturedDocs.get(capturedDocs.size() - 1));
            }
            if (downloadSeq > downloadsBefore) {
                currentDownloadName = null;
                return new Outcome(spec.key(), format, period, nav, "DOWNLOADED",
                        allDownloads.get(allDownloads.size() - 1));
            }
            String error = readPortalError(page);
            if (!error.isBlank()) {
                currentDownloadName = null;
                snapshot(page, outDir, baseName + "-ERROR");
                return new Outcome(spec.key(), format, period, nav, "PORTAL_ERROR", error);
            }
            page.waitForTimeout(250);
        }
        currentDownloadName = null;
        snapshot(page, outDir, baseName + "-TIMEOUT");
        return new Outcome(spec.key(), format, period, nav, "TIMEOUT", "no document after 45s");
    }

    /**
     * Navigates to the report page. Tries the plain URL first — if that works
     * the adapter can skip menu traversal entirely — and falls back to the
     * menu's own submit when the URL alone doesn't produce the form.
     */
    private static String gotoReportPage(Page page, ReportSpec spec) {
        page.navigate(AUTOGESTION + spec.navPath());
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        boolean ready = spec.hasForm()
                ? page.locator("form[id=\"formAPL\"]").count() > 0
                : page.locator("li[id=\"" + spec.menuId() + "\"]").count() > 0;
        if (ready) {
            return "direct-url";
        }
        // The menu item posts goTo=<path> and lets the server route. Invoking
        // its own onclick beats clicking, because the sidebar section may be
        // collapsed and an invisible element is not clickable.
        page.evaluate("path => PrimeFaces.addSubmitParam('menuform',"
                + "{'goTo': path, '%s': '%s'}).submit('menuform')".formatted(spec.menuId(), spec.menuId()),
                spec.navPath());
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        return "menu-submit";
    }

    /**
     * Sets the required Período (MM/yyyy). The field is a jQuery datepicker,
     * so typing pops a calendar overlay that can cover the Imprimir button;
     * the overlay is hidden directly rather than dismissed with Escape, which
     * the datepicker treats as "revert" and would clear the value again.
     */
    private static void setPeriod(Page page, String period) {
        var input = page.locator("input[id=\"formAPL:fecDesde_input\"]");
        if (input.count() == 0) {
            return;
        }
        input.fill(period);
        if (!period.equals(periodValue(page))) {
            // The datepicker can swallow a synthetic fill; write it straight
            // to the element and tell JSF about it.
            page.evaluate("val => { const el = document.getElementById('formAPL:fecDesde_input');"
                    + " el.value = val;"
                    + " el.dispatchEvent(new Event('input', { bubbles: true }));"
                    + " el.dispatchEvent(new Event('change', { bubbles: true })); }", period);
        }
        page.evaluate("() => { const dp = document.getElementById('ui-datepicker-div');"
                + " if (dp) { dp.style.setProperty('display', 'none', 'important'); } }");
    }

    private static String periodValue(Page page) {
        Object value = page.evaluate(
                "() => { const el = document.getElementById('formAPL:fecDesde_input');"
                + " return el ? el.value : ''; }");
        return value == null ? "" : value.toString().trim();
    }

    /**
     * Mirrors the known-good manual request, which posted sectorSelect=1. The
     * portal did not flag an empty Sector, so this is best-effort: never fail
     * the attempt over it.
     */
    private static void setSectorIfEmpty(Page page) {
        try {
            page.evaluate("() => { const s = document.getElementById('formAPL:sectorSelect_input');"
                    + " if (s && !s.value) { s.value = '1';"
                    + " s.dispatchEvent(new Event('change', { bubbles: true })); } }");
        } catch (RuntimeException ignored) {
            // Optional field — the period is the one the portal validates.
        }
    }

    /**
     * Selects the output format, preferring the visible PrimeFaces widget and
     * falling back to the native select it wraps.
     *
     * <p>The widget builds its item panel lazily and appends it to the body,
     * so the panel is absent from a page snapshot and the UI path can't be
     * verified ahead of time. What can be relied on is the postback: the HAR
     * shows the form serialises {@code formAPL:tipFormato_input}, the native
     * select, so that value is both the fallback and the thing worth
     * asserting — the styled label is cosmetic.
     *
     * @return the format actually staged for submission
     */
    private static String setFormat(Page page, String format) {
        if (page.locator("select[id=\"formAPL:tipFormato_input\"]").count() == 0) {
            return format;
        }
        try {
            page.locator("div[id=\"formAPL:tipFormato\"]").first()
                    .click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(3_000));
            String wanted = switch (format) {
                case "xls" -> "Formato Excel";
                case "txt" -> "Formato .txt";
                default -> "Formato .pdf";
            };
            page.locator("ul[id=\"formAPL:tipFormato_items\"] li[data-label=\"" + wanted + "\"]")
                    .first().click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(3_000));
        } catch (RuntimeException widgetPathUnavailable) {
            // Fall through to the select below.
        }
        if (!format.equals(currentFormat(page))) {
            page.evaluate("fmt => { const s = document.getElementById('formAPL:tipFormato_input');"
                    + " s.value = fmt; s.dispatchEvent(new Event('change', { bubbles: true })); }",
                    format);
        }
        return currentFormat(page);
    }

    private static String currentFormat(Page page) {
        Object value = page.evaluate(
                "() => { const s = document.getElementById('formAPL:tipFormato_input');"
                + " return s ? s.value : ''; }");
        return value == null ? "" : value.toString();
    }

    private static void clickTrigger(Page page, ReportSpec spec) {
        var trigger = page.locator(spec.trigger()).first();
        try {
            trigger.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(5_000));
        } catch (RuntimeException notClickable) {
            // Collapsed sidebar entries are present but not visible; running
            // the element's own handler is the same code path the click takes.
            String onclick = (String) page.evalOnSelector(
                    spec.trigger(), "el => el.getAttribute('onclick')");
            if (onclick == null || onclick.isBlank()) {
                throw notClickable;
            }
            page.evaluate("() => { " + onclick + " }");
        }
    }

    /**
     * Intercepts the report request and keeps the real bytes.
     *
     * <p>{@code route.fetch()} performs the paused request itself and hands
     * back the true response, which is then replayed to the browser. The
     * server therefore sees exactly one request — important, because the
     * report is selected by one-shot session state, so an extra GET could
     * return something else or nothing. This is also the only way to obtain a
     * PDF that the portal serves inline: by the time Chromium has it, the
     * bytes belong to the viewer plugin.
     */
    private static void captureDocument(Route route) {
        APIResponse api;
        try {
            api = route.fetch();
        } catch (RuntimeException fetchFailed) {
            // Never fail quietly here: a silent resume looks exactly like "the
            // portal sent nothing", and the document is then lost with no clue
            // as to why.
            System.out.println("    [DOC] intercept failed for " + route.request().url()
                    + " -> " + cell(String.valueOf(fetchFailed.getMessage())));
            route.resume();
            return;
        }
        try {
            byte[] bytes = api.body();
            String base = currentDownloadName == null
                    ? "doc-" + (++downloadSeq)
                    : slug(currentDownloadName);
            String name = base + extensionFor(api.headers());
            Files.write(downloadsDir.resolve(name), bytes);
            String line = "%s  %s  (%,d bytes, sniffed %s)  from %s".formatted(
                    LocalTime.now().format(CLOCK), name, bytes.length, sniff(bytes), route.request().url());
            capturedDocs.add(line);
            allDownloads.add(line);
            System.out.println("    [DOC] " + line);
        } catch (IOException | RuntimeException e) {
            System.out.println("    [DOC] capture failed: " + e.getMessage());
        }
        try {
            route.fulfill(new Route.FulfillOptions().setResponse(api));
        } catch (RuntimeException alreadyHandled) {
            // The browser no longer needs it; we already have the bytes.
        }
    }

    /**
     * Identifies what actually landed. A JSF report portal will happily serve
     * Excel-flavoured HTML as {@code .xls} and a viewer wrapper as a PDF, so
     * the extension proves nothing — the magic bytes do.
     */
    private static String sniff(byte[] bytes) {
        if (bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
            return "PDF";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF) {
            return "XLS (OLE2)";
        }
        if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K') {
            return "ZIP/XLSX";
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.UTF_8)
                .trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
            return head.contains("urn:schemas-microsoft-com:office") ? "HTML (Excel-flavoured)" : "HTML";
        }
        return "unknown";
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
        if (contentType.contains("text/plain")) {
            return ".txt";
        }
        return ".bin";
    }

    /** Reads the JSF messages panel — the only place a rejected request shows up. */
    private static String readPortalError(Page page) {
        try {
            var errors = page.locator(
                    "[id=\"formAPL:messages\"] .ui-messages-error-summary, "
                    + "[id=\"formAPL:messages\"] .ui-messages-fatal-summary");
            if (errors.count() == 0) {
                return "";
            }
            return errors.first().innerText().trim();
        } catch (RuntimeException pageBusy) {
            return "";
        }
    }

    private static void snapshot(Page page, Path outDir, String name) {
        try {
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(outDir.resolve("drive-" + slug(name) + ".png")).setFullPage(true));
            Files.writeString(outDir.resolve("drive-" + slug(name) + ".html"), page.content());
        } catch (RuntimeException | IOException ignored) {
            // Diagnostics only — never fail the pass over a snapshot.
        }
    }

    /** Popups holding a rendered document pile up; keep only the driving page. */
    private static void closeExtraPages(BrowserContext context, Page keep) {
        for (Page p : context.pages()) {
            if (p != keep && !p.isClosed()) {
                try {
                    p.close();
                } catch (RuntimeException ignored) {
                    // Already gone.
                }
            }
        }
    }

    private static void writeDriveReport(Path outDir, List<String> periods, List<Outcome> outcomes)
            throws IOException {
        StringBuilder sb = new StringBuilder("# SICERE drive-mode results\n\n");
        sb.append("periods tried, in order: ").append(periods).append("\n\n");
        sb.append("| report | format | period used | navigation | status | detail |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (Outcome o : outcomes) {
            sb.append("| ").append(o.report()).append(" | ").append(o.format())
              .append(" | ").append(o.period().isBlank() ? "n/a" : o.period())
              .append(" | ").append(o.nav()).append(" | ").append(o.status())
              .append(" | ").append(cell(o.detail())).append(" |\n");
        }
        sb.append("\n## What this answers\n\n");
        sb.append("- `navigation` = direct-url means the adapter can navigate straight to the\n");
        sb.append("  report page; menu-submit means it must go through the menu handler.\n");
        sb.append("- `period used` is the period the portal actually accepted for that report,\n");
        sb.append("  which is what the adapter must derive per report rather than assume.\n");
        sb.append("- A PORTAL_ERROR row is the portal rejecting the request, not a bug — the\n");
        sb.append("  message text is what the adapter must surface.\n");
        Files.writeString(outDir.resolve("drive-report.md"), sb.toString());
        System.out.println();
        System.out.println(sb);
    }

    private static void banner(String startUrl, Path outDir) {
        System.out.println();
        System.out.println("=== SICERE report-capture spike ===");
        System.out.println("Browser open at: " + startUrl);
        System.out.println("Output dir     : " + outDir.toAbsolutePath());
        System.out.println();
        System.out.println("1. Log in manually in the window (username -> Continuar -> password -> Ingresar).");
        System.out.println("2. Walk to each report you want the bot to save. Open/download it as you");
        System.out.println("   normally would - PDFs are captured automatically and printed here.");
        System.out.println("3. At each screen worth remembering, type a short label and press Enter.");
        System.out.println("   e.g. 'planilla confirmada', 'menu reportes', 'comprobante de envio'");
        System.out.println("4. Type 'q' when you've shown me the whole flow.");
    }

    /**
     * Waits for a line of input while keeping the Playwright event loop turning.
     * Playwright Java dispatches events only inside API calls, so blocking
     * straight on {@code readLine()} would freeze download reporting until the
     * next Enter — pumping a short timeout keeps the [PDF] lines arriving as
     * you click. Input is read on a daemon thread so a detached stdin reports
     * EOF (and ends the run) instead of spinning here forever.
     */
    private static String promptPumped(BrowserContext context, String message) {
        System.out.println();
        System.out.print(">>> " + message + " ");
        System.out.flush();
        String line;
        while ((line = INPUT.poll()) == null) {
            pump(context);
        }
        return EOF.equals(line) ? null : line;
    }

    private static void startInputReader() {
        Thread reader = new Thread(() -> {
            try (BufferedReader stdin = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = stdin.readLine()) != null) {
                    INPUT.put(line);
                }
            } catch (IOException | InterruptedException ignored) {
                // Fall through to EOF — the run ends and artifacts still flush.
            }
            try {
                INPUT.put(EOF);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "spike-stdin");
        reader.setDaemon(true);
        reader.start();
    }

    private static void pump(BrowserContext context) {
        try {
            List<Page> pages = context.pages();
            if (pages.isEmpty()) {
                Thread.sleep(200);
                return;
            }
            pages.get(0).waitForTimeout(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException pageClosedOrNavigating) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Flattens a detail string into one table cell. Playwright stack traces and
     * portal messages both carry newlines, which would otherwise split a row
     * across lines and destroy the table.
     */
    private static String cell(String raw) {
        if (raw == null) {
            return "";
        }
        String flat = raw.replaceAll("\\s+", " ").replace("|", "\\|").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + " ..." : flat;
    }

    /** Keeps the portal's own extension — it reveals what xls actually returns. */
    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot) : "";
    }

    private static String slug(String raw) {
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (cleaned.isBlank()) {
            cleaned = "screen";
        }
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }
}
