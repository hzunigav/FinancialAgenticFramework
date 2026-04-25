package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.AwsSecretsManagerCredentialsProvider;
import com.neoproc.financialagent.common.credentials.CredentialsProvider;
import com.neoproc.financialagent.common.credentials.LocalFileCredentialsProvider;
import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.common.session.LocalEncryptedSessionStore;
import com.neoproc.financialagent.common.session.SessionStore;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitRequest;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import com.neoproc.financialagent.worker.portal.HarScrubber;
import com.neoproc.financialagent.worker.portal.PortalDescriptor;
import com.neoproc.financialagent.worker.portal.PortalDescriptorLoader;
import com.neoproc.financialagent.worker.portal.PortalEngine;
import com.neoproc.financialagent.worker.portal.PortalRateLimiter;
import com.neoproc.financialagent.worker.portal.PortalScraper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.HarContentPolicy;
import com.microsoft.playwright.options.LoadState;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Core portal-interaction service. Instantiated as a plain Java object so
 * it works identically from the Spring Boot queue consumer
 * ({@link com.neoproc.financialagent.worker.listener.PayrollTaskListener})
 * and from the CLI dev entry point ({@link Agent#main}).
 *
 * <p>A fresh {@link PortalAdapter} is created per run to ensure stateful
 * adapters (e.g. {@link MockPayrollAdapter}) never share state across calls.
 */
public final class PortalRunService {

    private static final Logger log = LoggerFactory.getLogger(PortalRunService.class);

    private static final DateTimeFormatter RUN_ID_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private final Path artifactsRoot;

    public PortalRunService(Path artifactsRoot) {
        this.artifactsRoot = artifactsRoot;
    }

    /**
     * Executes a complete portal run.
     *
     * @param portalId       descriptor id (e.g. {@code "mock-payroll"})
     * @param submitRequest  BPM-shape submit envelope; when non-null it is
     *                       written to the run directory and set as the
     *                       {@code params.source.submitRequest} binding so
     *                       submit adapters can read it. Pass {@code null}
     *                       for CLI runs where the path comes from
     *                       {@code -Dparams.source.submitRequest}.
     * @param extraBindings  {@code params.*} and other runtime bindings that
     *                       supplement (and may override) the credential
     *                       bindings; must contain at least
     *                       {@code params.firmId}.
     * @return outcome containing the run directory and terminal status
     */
    public RunOutcome run(String portalId,
                          PayrollSubmitRequest submitRequest,
                          Map<String, String> extraBindings)
            throws IOException, InterruptedException {

        PortalDescriptor descriptor = PortalDescriptorLoader.load(portalId);
        PortalAdapter adapter = newAdapter(portalId);

        String firmId = extraBindings.getOrDefault("params.firmId", "1");
        CredentialsProvider credentialsProvider = buildCredentialsProvider(descriptor, firmId);
        PortalCredentials credentials = credentialsProvider.get(portalId);

        Map<String, String> bindings = buildBindings(credentials, extraBindings);
        Map<String, List<Map<String, String>>> listBindings = new HashMap<>();

        String runId = newRunId();
        Path runDir = artifactsRoot.resolve(runId);
        Files.createDirectories(runDir);
        bindings.put("runtime.runId", runId);
        bindings.put("runtime.runDir", runDir.toString());

        // Write the BPM submit envelope to disk so submit adapters can read
        // it via the params.source.submitRequest binding.
        if (submitRequest != null) {
            Path requestFile = runDir.resolve("payroll-submit-request.v1.json");
            EnvelopeIo.write(submitRequest, requestFile);
            bindings.put("params.source.submitRequest", requestFile.toString());
        }

        // MDC: issuerRunId falls back to local runId for CLI-mode runs that
        // have no Praxis envelope.
        MDC.put("issuerRunId", bindings.getOrDefault("params.issuerRunId", runId));
        MDC.put("firmId", firmId);
        if (bindings.containsKey("params.businessKey")) {
            MDC.put("businessKey", bindings.get("params.businessKey"));
        }

        SessionStore sessionStore = new LocalEncryptedSessionStore();
        Optional<String> savedSession = loadSavedSession(sessionStore, descriptor);

        RunManifest manifest = new RunManifest();
        manifest.runId = runId;
        manifest.startedAt = Instant.now();
        manifest.portal.id = descriptor.id();
        manifest.portal.baseUrl = descriptor.baseUrl();
        manifest.portal.username = credentials.require("username");
        manifest.portal.shadowMode = descriptor.isShadowMode();
        manifest.portal.sessionReused = savedSession.isPresent();
        manifest.agentWorkerVersion = version(PortalRunService.class.getPackage().getImplementationVersion());
        manifest.playwrightVersion = version(Playwright.class.getPackage().getImplementationVersion());

        log.info("run started portal={} shadowMode={} sessionReused={}",
                descriptor.id(), descriptor.isShadowMode(), savedSession.isPresent());

        Path manifestPath = runDir.resolve("manifest.json");
        long runStartNanos = System.nanoTime();
        try (PortalRateLimiter.Permit ignored = PortalRateLimiter.acquire(descriptor);
             Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions().setHeadless(true));
             BrowserContext context = browser.newContext(newContextOptions(runDir, savedSession))) {

            context.tracing().start(new Tracing.StartOptions()
                    .setScreenshots(true)
                    .setSnapshots(true)
                    .setSources(true));

            Page page = context.newPage();

            PortalEngine engine = new PortalEngine(
                    page, bindings, listBindings, manifest::step,
                    stdinOperatorInput(), descriptor.isShadowMode());

            if (savedSession.isEmpty()) {
                engine.runSteps(descriptor.baseUrl(), descriptor.authSteps());
                saveSessionIfEnabled(sessionStore, descriptor, context);
            } else {
                page.navigate(descriptor.baseUrl());
                page.waitForLoadState(LoadState.NETWORKIDLE);
                manifest.step("auth-skipped",
                        "session-reused; navigated to " + descriptor.baseUrl() + " (networkidle)");
            }

            adapter.beforeSteps(descriptor, page, bindings, listBindings, credentials, manifest);

            engine.runSteps(descriptor.baseUrl(), descriptor.steps());

            manifest.step("screenshot", "report.png");
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(runDir.resolve("report.png"))
                    .setFullPage(true));

            if (Boolean.parseBoolean(System.getProperty("fixture.capture", "false"))) {
                Path fixturesDir = runDir.resolve("fixtures");
                Files.createDirectories(fixturesDir);
                Path fixtureFile = fixturesDir.resolve(descriptor.id() + "-post-steps.html");
                Files.writeString(fixtureFile, page.content());
                manifest.step("fixture-capture",
                        fixtureFile.getFileName() + " (" + Files.size(fixtureFile) + " bytes)");
            }

            manifest.step("scrape", fieldNames(descriptor));
            PortalScraper scraper = new PortalScraper(page);
            Map<String, String> scraped = scraper.scrape(descriptor.scrape());
            List<Map<String, String>> scrapedRows = List.of();
            PortalDescriptor.Scrape.RowSpec rows = descriptor.scrape() != null
                    ? descriptor.scrape().rows()
                    : null;
            if (rows != null && rows.selector() != null) {
                scrapedRows = scraper.scrapeRows(rows.selector(), rows.columns());
                manifest.step("scrape-rows",
                        rows.selector() + " -> " + scrapedRows.size() + " rows");
            }

            manifest.finalUrl = page.url();
            manifest.finalTitle = page.title();
            manifest.status = adapter.captureToManifest(
                    scraped, scrapedRows, bindings, credentials, manifest);

            context.tracing().stop(new Tracing.StopOptions()
                    .setPath(runDir.resolve("trace.zip")));

        } catch (PortalEngine.ShadowHalt e) {
            manifest.status = "SHADOW_HALT";
            manifest.error = e.getMessage();
        } catch (RuntimeException e) {
            manifest.status = "FAILED";
            manifest.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw e;
        } finally {
            new HarScrubber(descriptor.securityContext()).scrub(runDir.resolve("network.har"));
            manifest.finishedAt = Instant.now();
            EnvelopeIo.MAPPER.writeValue(manifestPath.toFile(), manifest);
            log.info("run complete status={} envelopeId={} businessKey={}",
                    manifest.status, manifest.envelopeId, manifest.businessKey);
            String metricName = adapter instanceof AbstractCaptureAdapter
                    ? "agent_capture_duration_seconds"
                    : "agent_submit_duration_seconds";
            String terminalStatus = manifest.status != null ? manifest.status : "UNKNOWN";
            Timer.builder(metricName)
                    .tags("portal", portalId, "status", terminalStatus)
                    .register(Metrics.globalRegistry)
                    .record(System.nanoTime() - runStartNanos, TimeUnit.NANOSECONDS);
        }

        return new RunOutcome(runDir, manifest.status);
    }

    // --- adapter registry ---------------------------------------------------

    static PortalAdapter newAdapter(String portalId) {
        return switch (portalId) {
            case "mock-portal"  -> new MockPortalAdapter();
            case "mock-payroll" -> new MockPayrollAdapter();
            case "autoplanilla" -> new AutoplanillaAdapter();
            default -> throw new IllegalStateException(
                    "No PortalAdapter registered for portal: " + portalId);
        };
    }

    // --- helpers ------------------------------------------------------------

    private static CredentialsProvider buildCredentialsProvider(
            PortalDescriptor descriptor, String firmId) {
        if ("aws".equalsIgnoreCase(System.getenv("FINANCEAGENT_CREDENTIALS"))) {
            return new AwsSecretsManagerCredentialsProvider(
                    firmId, id -> descriptor.credentialScope());
        }
        return new LocalFileCredentialsProvider();
    }

    static Map<String, String> buildBindings(PortalCredentials credentials,
                                              Map<String, String> extraBindings) {
        Map<String, String> bindings = new LinkedHashMap<>();
        credentials.values().forEach((k, v) -> bindings.put("credentials." + k, v));
        bindings.putAll(extraBindings);
        return bindings;
    }

    private static Browser.NewContextOptions newContextOptions(
            Path runDir, Optional<String> savedSession) {
        Browser.NewContextOptions opts = new Browser.NewContextOptions()
                .setRecordHarPath(runDir.resolve("network.har"))
                .setRecordHarContent(HarContentPolicy.EMBED);
        savedSession.ifPresent(opts::setStorageState);
        return opts;
    }

    private static Optional<String> loadSavedSession(
            SessionStore store, PortalDescriptor descriptor) {
        PortalDescriptor.SessionConfig session = descriptor.session();
        if (session == null || !session.enabled()) {
            return Optional.empty();
        }
        return store.load(descriptor.id(), Duration.ofMinutes(session.ttlMinutes()));
    }

    private static void saveSessionIfEnabled(SessionStore store,
                                              PortalDescriptor descriptor,
                                              BrowserContext context) {
        PortalDescriptor.SessionConfig session = descriptor.session();
        if (session == null || !session.enabled()) {
            return;
        }
        store.save(descriptor.id(), context.storageState());
    }

    private static Function<String, String> stdinOperatorInput() {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return prompt -> {
            System.out.print("[operator] " + prompt + " > ");
            System.out.flush();
            try {
                String line = in.readLine();
                return line == null ? "" : line.trim();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static String fieldNames(PortalDescriptor descriptor) {
        if (descriptor.scrape() == null) return "";
        return descriptor.scrape().fields().stream()
                .map(PortalDescriptor.Scrape.Field::name)
                .collect(Collectors.joining(","));
    }

    static String newRunId() {
        String suffix = String.format(Locale.ROOT, "%05x",
                ThreadLocalRandom.current().nextInt(0x100000));
        return RUN_ID_FMT.format(Instant.now()) + "-" + suffix;
    }

    private static String version(String fromManifest) {
        return fromManifest != null ? fromManifest : "dev";
    }
}
