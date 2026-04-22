package com.financialagent.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

public class Agent {

    private static final DateTimeFormatter RUN_ID_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static void main(String[] args) throws IOException {
        Properties config = loadConfig();
        String portalUrl = config.getProperty("portal.url");
        String username = config.getProperty("portal.username");
        String password = config.getProperty("portal.password");
        Path artifactsRoot = Paths.get(config.getProperty("artifacts.dir", "artifacts"))
                .toAbsolutePath().normalize();

        String runId = newRunId();
        Path runDir = artifactsRoot.resolve(runId);
        Files.createDirectories(runDir);

        RunManifest manifest = new RunManifest();
        manifest.runId = runId;
        manifest.startedAt = Instant.now();
        manifest.portal.url = portalUrl;
        manifest.portal.username = username;
        manifest.agentWorkerVersion = version(Agent.class.getPackage().getImplementationVersion());
        manifest.playwrightVersion = version(Playwright.class.getPackage().getImplementationVersion());

        System.out.println("Run " + runId);
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

            manifest.step("navigate", portalUrl + "/login");
            page.navigate(portalUrl + "/login");

            manifest.step("fill", "input[name='username']");
            page.fill("input[name='username']", username);

            manifest.step("fill", "input[name='password'] (value redacted)");
            page.fill("input[name='password']", password);

            manifest.step("click", "button[type='submit']");
            page.click("button[type='submit']");

            manifest.step("waitForURL", "**/report");
            page.waitForURL("**/report");

            manifest.step("screenshot", "report.png");
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(runDir.resolve("report.png"))
                    .setFullPage(true));

            manifest.finalUrl = page.url();
            manifest.finalTitle = page.title();
            manifest.status = "SUCCESS";

            context.tracing().stop(new Tracing.StopOptions()
                    .setPath(runDir.resolve("trace.zip")));
        } catch (RuntimeException e) {
            manifest.status = "FAILED";
            manifest.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw e;
        } finally {
            manifest.finishedAt = Instant.now();
            MAPPER.writeValue(manifestPath.toFile(), manifest);
            System.out.println("Status: " + manifest.status);
            System.out.println("Manifest: " + manifestPath);
        }
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
