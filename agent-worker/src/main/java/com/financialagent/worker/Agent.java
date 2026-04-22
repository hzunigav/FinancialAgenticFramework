package com.financialagent.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.financialagent.common.credentials.CredentialsProvider;
import com.financialagent.common.credentials.LocalFileCredentialsProvider;
import com.financialagent.common.credentials.PortalCredentials;
import com.financialagent.common.domain.ReportSnapshot;
import com.financialagent.common.verify.ReadBackVerifier;
import com.financialagent.common.verify.VerificationResult;
import com.financialagent.worker.portal.HarScrubber;
import com.financialagent.worker.portal.PortalDescriptor;
import com.financialagent.worker.portal.PortalDescriptorLoader;
import com.financialagent.worker.portal.PortalEngine;
import com.financialagent.worker.portal.PortalScraper;
import com.financialagent.worker.portal.SnapshotMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.HarContentPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class Agent {

    private static final DateTimeFormatter RUN_ID_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static void main(String[] args) throws IOException {
        Properties config = loadConfig();
        String portalId = config.getProperty("portal.id");
        Path artifactsRoot = Paths.get(config.getProperty("artifacts.dir", "artifacts"))
                .toAbsolutePath().normalize();

        PortalDescriptor descriptor = PortalDescriptorLoader.load(portalId);

        CredentialsProvider credentialsProvider = new LocalFileCredentialsProvider();
        PortalCredentials credentials = credentialsProvider.get(portalId);
        Map<String, String> bindings = new LinkedHashMap<>();
        credentials.values().forEach((k, v) -> bindings.put("credentials." + k, v));

        String runId = newRunId();
        Path runDir = artifactsRoot.resolve(runId);
        Files.createDirectories(runDir);

        RunManifest manifest = new RunManifest();
        manifest.runId = runId;
        manifest.startedAt = Instant.now();
        manifest.portal.id = descriptor.id();
        manifest.portal.baseUrl = descriptor.baseUrl();
        manifest.portal.username = credentials.require("username");
        manifest.agentWorkerVersion = version(Agent.class.getPackage().getImplementationVersion());
        manifest.playwrightVersion = version(Playwright.class.getPackage().getImplementationVersion());

        System.out.println("Run " + runId);
        System.out.println("Portal: " + descriptor.id() + " (" + descriptor.baseUrl() + ")");
        System.out.println("Artifacts: " + runDir);

        Path manifestPath = runDir.resolve("manifest.json");
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions().setHeadless(true));
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setRecordHarPath(runDir.resolve("network.har"))
                     .setRecordHarContent(HarContentPolicy.EMBED))) {

            context.tracing().start(new Tracing.StartOptions()
                    .setScreenshots(true)
                    .setSnapshots(true)
                    .setSources(true));

            Page page = context.newPage();

            PortalEngine engine = new PortalEngine(page, bindings, manifest::step);
            engine.run(descriptor);

            manifest.step("screenshot", "report.png");
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(runDir.resolve("report.png"))
                    .setFullPage(true));

            manifest.step("scrape", fieldNames(descriptor));
            Map<String, String> scraped = new PortalScraper(page).scrape(descriptor.scrape());
            ReportSnapshot scrapedSnapshot = SnapshotMapper.toSnapshot(scraped);
            ReportSnapshot sourceSnapshot = new ReportSnapshot(
                    credentials.require("username"), LocalDate.now());

            VerificationResult<ReportSnapshot> result =
                    ReadBackVerifier.verify(sourceSnapshot, scrapedSnapshot);
            manifest.verification = toManifestVerification(result);
            manifest.step("verify", result.status().name());

            manifest.finalUrl = page.url();
            manifest.finalTitle = page.title();
            manifest.status = result.matched() ? "SUCCESS" : "MISMATCH";

            context.tracing().stop(new Tracing.StopOptions()
                    .setPath(runDir.resolve("trace.zip")));
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

    private static RunManifest.Verification toManifestVerification(
            VerificationResult<ReportSnapshot> result) {
        RunManifest.Verification v = new RunManifest.Verification();
        v.status = result.status().name();
        v.source = result.source();
        v.scraped = result.scraped();
        v.diffs = result.diffs().stream()
                .map(d -> new RunManifest.Verification.Diff(d.field(), d.source(), d.scraped()))
                .toList();
        return v;
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
