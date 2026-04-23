package com.neoproc.financialagent.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neoproc.financialagent.common.credentials.CredentialsProvider;
import com.neoproc.financialagent.common.credentials.LocalFileCredentialsProvider;
import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.common.session.LocalEncryptedSessionStore;
import com.neoproc.financialagent.common.session.SessionStore;
import com.neoproc.financialagent.worker.portal.HarScrubber;
import com.neoproc.financialagent.worker.portal.PortalDescriptor;
import com.neoproc.financialagent.worker.portal.PortalDescriptorLoader;
import com.neoproc.financialagent.worker.portal.PortalEngine;
import com.neoproc.financialagent.worker.portal.PortalScraper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.HarContentPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Agent {

    private static final DateTimeFormatter RUN_ID_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    static final Map<String, PortalAdapter> ADAPTERS = Map.of(
            "mock-portal", new MockPortalAdapter(),
            "mock-payroll", new MockPayrollAdapter(),
            "autoplanilla", new AutoplanillaAdapter());

    public static void main(String[] args) throws IOException {
        Properties config = loadConfig();
        String portalId = System.getProperty("portal.id", config.getProperty("portal.id"));
        Path artifactsRoot = Paths.get(config.getProperty("artifacts.dir", "artifacts"))
                .toAbsolutePath().normalize();

        PortalDescriptor descriptor = PortalDescriptorLoader.load(portalId);
        PortalAdapter adapter = ADAPTERS.get(portalId);
        if (adapter == null) {
            throw new IllegalStateException("No PortalAdapter registered for portal: " + portalId);
        }

        CredentialsProvider credentialsProvider = new LocalFileCredentialsProvider();
        PortalCredentials credentials = credentialsProvider.get(portalId);
        Map<String, String> bindings = buildBindings(credentials);
        Map<String, List<Map<String, String>>> listBindings = new HashMap<>();
        bindings.putIfAbsent("params.firmId", "1");

        SessionStore sessionStore = new LocalEncryptedSessionStore();
        Optional<String> savedSession = loadSavedSession(sessionStore, descriptor);

        String runId = newRunId();
        Path runDir = artifactsRoot.resolve(runId);
        Files.createDirectories(runDir);
        bindings.put("runtime.runId", runId);
        bindings.put("runtime.runDir", runDir.toString());

        RunManifest manifest = new RunManifest();
        manifest.runId = runId;
        manifest.startedAt = Instant.now();
        manifest.portal.id = descriptor.id();
        manifest.portal.baseUrl = descriptor.baseUrl();
        manifest.portal.username = credentials.require("username");
        manifest.portal.shadowMode = descriptor.isShadowMode();
        manifest.portal.sessionReused = savedSession.isPresent();
        manifest.agentWorkerVersion = version(Agent.class.getPackage().getImplementationVersion());
        manifest.playwrightVersion = version(Playwright.class.getPackage().getImplementationVersion());

        System.out.println("Run " + runId);
        System.out.println("Portal: " + descriptor.id() + " (" + descriptor.baseUrl() + ")");
        System.out.println("Shadow mode: " + descriptor.isShadowMode());
        System.out.println("Session reused: " + savedSession.isPresent());
        System.out.println("Artifacts: " + runDir);

        Path manifestPath = runDir.resolve("manifest.json");
        try (Playwright playwright = Playwright.create();
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
                manifest.step("auth-skipped", "session-reused");
            }

            adapter.beforeSteps(descriptor, page, bindings, listBindings, credentials, manifest);

            engine.runSteps(descriptor.baseUrl(), descriptor.steps());

            manifest.step("screenshot", "report.png");
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(runDir.resolve("report.png"))
                    .setFullPage(true));

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
            MAPPER.writeValue(manifestPath.toFile(), manifest);
            System.out.println("Status: " + manifest.status);
            System.out.println("Manifest: " + manifestPath);
        }
    }

    /**
     * Build the bindings map for a run:
     * <ul>
     *   <li>{@code credentials.*} from the {@link PortalCredentials}.</li>
     *   <li>{@code params.*} from {@code -D} system properties, so dates
     *       and planilla names can vary per invocation without editing
     *       the descriptor.</li>
     * </ul>
     */
    private static Map<String, String> buildBindings(PortalCredentials credentials) {
        Map<String, String> bindings = new LinkedHashMap<>();
        credentials.values().forEach((k, v) -> bindings.put("credentials." + k, v));
        System.getProperties().forEach((k, v) -> {
            String key = k.toString();
            if (key.startsWith("params.")) {
                bindings.put(key, v.toString());
            }
        });
        return bindings;
    }

    private static Browser.NewContextOptions newContextOptions(Path runDir, Optional<String> savedSession) {
        Browser.NewContextOptions opts = new Browser.NewContextOptions()
                .setRecordHarPath(runDir.resolve("network.har"))
                .setRecordHarContent(HarContentPolicy.EMBED);
        savedSession.ifPresent(opts::setStorageState);
        return opts;
    }

    private static Optional<String> loadSavedSession(SessionStore store, PortalDescriptor descriptor) {
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
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
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
        return descriptor.scrape().fields().stream()
                .map(PortalDescriptor.Scrape.Field::name)
                .collect(Collectors.joining(","));
    }

    private static String newRunId() {
        String suffix = String.format(Locale.ROOT, "%05x",
                ThreadLocalRandom.current().nextInt(0x100000));
        return RUN_ID_FMT.format(Instant.now()) + "-" + suffix;
    }

    private static String version(String fromManifest) {
        return fromManifest != null ? fromManifest : "dev";
    }

    private static Properties loadConfig() throws IOException {
        Properties props = new Properties();
        try (InputStream in = Agent.class.getResourceAsStream("/config.properties")) {
            if (in == null) {
                throw new IllegalStateException("config.properties not found on classpath");
            }
            props.load(in);
        }
        return props;
    }
}
