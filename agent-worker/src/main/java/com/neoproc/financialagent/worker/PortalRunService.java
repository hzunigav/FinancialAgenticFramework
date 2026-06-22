package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.AwsSecretsManagerCredentialsProvider;
import com.neoproc.financialagent.common.credentials.CredentialsProvider;
import com.neoproc.financialagent.common.credentials.LocalFileCredentialsProvider;
import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.common.crypto.EnvelopeCipher;
import com.neoproc.financialagent.common.session.SessionStores;
import com.neoproc.financialagent.contract.bankstatement.BankStatementUploadRequest;
import com.neoproc.financialagent.contract.bankstatement.FileBody;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitRequest;
import com.neoproc.financialagent.worker.artifact.S3ArtifactStore;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import com.neoproc.financialagent.worker.portal.HarScrubber;
import com.neoproc.financialagent.worker.portal.PortalDescriptor;
import com.neoproc.financialagent.worker.portal.PortalDescriptorLoader;
import com.neoproc.financialagent.worker.portal.PortalEngine;
import com.neoproc.financialagent.worker.portal.PortalRateLimiter;
import com.neoproc.financialagent.worker.portal.PortalScraper;
import com.neoproc.financialagent.worker.auth.TotpGenerator;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
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
 *
 * <p>Authentication is handled by {@link PortalAuthService}, which is shared
 * between the full capture/submit path ({@link #run}) and the login-only
 * probe path ({@link #runProbe}).
 */
public class PortalRunService {

    private static final Logger log = LoggerFactory.getLogger(PortalRunService.class);

    private static final DateTimeFormatter RUN_ID_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    // Login-form tell on login.xero.com — present only when Xero actually
    // prompts (cold start / expired session); absent on a warm session reuse.
    // Mirrors xero.yaml's authSteps Username selector.
    private static final String XERO_USERNAME_INPUT = "[data-automationid='Username--input']";

    // 2FA challenge selectors. Xero only shows a TOTP prompt after the password
    // step when the trust-device cookie has expired (~30 days) or been revoked.
    // The exact DOM wasn't in the Phase-0 capture, so these are candidate sets
    // matching Xero's XUI conventions (X--input / X--button) plus generic
    // one-time-code fallbacks; the first real challenge is auto-dumped so they
    // can be hardened. The "trust this device" check re-mints the ~30-day cookie
    // so 2FA stays rare (≈ monthly per worker).
    private static final String XERO_2FA_CODE_INPUT = String.join(", ",
            "[data-automationid='AuthenticationCode--input']",
            "[data-automationid='Confirmation--input']",
            "[data-automationid='TwoStepAuthentication--input']",
            "[data-automationid='Totp--input']",
            "input[autocomplete='one-time-code']",
            "input[name='AuthenticationCode']",
            "input[name='Code']",
            "input[id*='totp' i]",
            "input[id*='authentication-code' i]");
    private static final String XERO_2FA_TRUST_CHECKBOX = String.join(", ",
            "[data-automationid='RememberMe--checkbox']",
            "[data-automationid='TrustThisDevice--checkbox']",
            "input[type='checkbox'][name*='emember' i]",
            "input[type='checkbox'][name*='rust' i]",
            "input[type='checkbox']");
    private static final String XERO_2FA_CONFIRM_BUTTON = String.join(", ",
            "[data-automationid='ConfirmAuthentication--button']",
            "[data-automationid='Confirm--button']",
            "[data-automationid='LoginSubmit--button']",
            "button[type='submit']");

    private final Path artifactsRoot;
    private final S3ArtifactStore artifactStore;

    public PortalRunService(Path artifactsRoot) {
        this(artifactsRoot, null);
    }

    public PortalRunService(Path artifactsRoot, S3ArtifactStore artifactStore) {
        this.artifactsRoot = artifactsRoot;
        this.artifactStore = artifactStore;
    }

    /**
     * Executes a complete portal run (capture or submit).
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

        boolean isCapture = submitRequest == null;
        String captureDescriptorId = portalId + "-capture";
        String descriptorId = isCapture && PortalDescriptorLoader.exists(captureDescriptorId)
                ? captureDescriptorId
                : portalId;
        PortalDescriptor descriptor = PortalDescriptorLoader.load(descriptorId);
        PortalAdapter adapter = newAdapter(portalId, isCapture);

        String firmId = extraBindings.getOrDefault("params.firmId", "1");
        String clientId = extraBindings.get("params.clientIdentifier");
        CredentialsProvider credentialsProvider = buildCredentialsProvider(descriptor, firmId);
        // Shared-scope portals (INS RT-Virtual, AutoPlanilla, Hacienda OVI)
        // keep one credential set per portalId — the path doesn't include
        // clientIdentifier on either AWS or the local file. The AWS provider
        // already ignores clientId for shared scope; the local provider keys
        // on clientId whenever it's non-blank, so for portals like INS that
        // need clientIdentifier for navigation but not for credential lookup,
        // null it out here so dev and prod resolve identically. clientId
        // continues to flow into the adapter via the bindings map below.
        String credClientId = "shared".equalsIgnoreCase(descriptor.credentialScope())
                ? null : clientId;
        PortalCredentials credentials = credentialsProvider.get(portalId, credClientId);

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

        // Load saved session for browser context setup (PortalAuthService also
        // loads it internally to decide whether to replay authSteps — the two
        // reads are cheap and idempotent).
        Optional<String> savedSession = PortalAuthService.loadSavedSession(
                SessionStores.defaultStore(), descriptor);

        RunManifest manifest = new RunManifest();
        manifest.runId = runId;
        manifest.startedAt = Instant.now();
        manifest.portal.id = descriptor.id();
        manifest.portal.baseUrl = descriptor.baseUrl();
        manifest.portal.username = credentials.require("username");
        manifest.portal.shadowMode = descriptor.isShadowMode();
        manifest.agentWorkerVersion = version(PortalRunService.class.getPackage().getImplementationVersion());
        manifest.playwrightVersion = version(Playwright.class.getPackage().getImplementationVersion());
        // Set BEFORE adapter.captureToManifest() so Audit.manifestPath
        // embeds the S3 URI. Null in local dev → adapters fall back to
        // "manifest.json".
        manifest.artifactUri = artifactStore != null ? artifactStore.urlFor(portalId, runId) : null;

        log.info("run started portal={} shadowMode={} sessionReused={}",
                descriptor.id(), descriptor.isShadowMode(), savedSession.isPresent());

        Path manifestPath = runDir.resolve("manifest.json");
        long runStartNanos = System.nanoTime();
        String uploadedUri = null;
        try (PortalRateLimiter.Permit ignored = PortalRateLimiter.acquire(descriptor);
             Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions().setHeadless(headless()));
             BrowserContext context = browser.newContext(newContextOptions(runDir, savedSession))) {

            context.tracing().start(new Tracing.StartOptions()
                    .setScreenshots(true)
                    .setSnapshots(true)
                    .setSources(true));

            Page page = context.newPage();

            PortalEngine engine = new PortalEngine(
                    page, bindings, listBindings, manifest::step,
                    stdinOperatorInput(), descriptor.isShadowMode());

            manifest.portal.sessionReused = PortalAuthService.login(
                    engine, descriptor, context, page, manifest);

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
                scrapedRows = scraper.scrapeAllRows(
                        rows.selector(), rows.columns(), rows.pagination());
                manifest.step("scrape-rows",
                        rows.selector() + " -> " + scrapedRows.size() + " rows"
                                + (rows.pagination() != null ? " (paginated)" : ""));
            }

            manifest.finalUrl = page.url();
            manifest.finalTitle = page.title();
            manifest.status = adapter.captureToManifest(
                    scraped, scrapedRows, bindings, credentials, manifest);

            // Teardown (logout, etc.) — best-effort. The run is already done
            // and the envelope is already on disk; a logout failure must not
            // flip a successful run to FAILED.
            try {
                adapter.afterCapture(page, bindings, manifest);
            } catch (RuntimeException teardownEx) {
                log.warn("afterCapture failed (non-fatal) portal={} error={}",
                        descriptor.id(), teardownEx.toString());
                manifest.step("teardown-failed", teardownEx.getClass().getSimpleName()
                        + ": " + teardownEx.getMessage());
            }

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
            // Upload all run-dir contents (manifest.json, report.png,
            // network.har, trace.zip, *.v1.json) to S3 so they survive
            // Fargate task shutdown. Failure is non-fatal — manifest.artifactUri
            // was already embedded in the envelope and audits can re-check.
            uploadedUri = artifactStore != null
                    ? artifactStore.uploadRunDir(runDir, portalId, runId)
                    : null;
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

        return new RunOutcome(runDir, manifest.status, null, uploadedUri);
    }

    /**
     * Executes a Xero bank-statement upload run. Parallel to {@link #run} but
     * for the bank-statement contract: stages the inbound request envelope and
     * decodes its CSV to the run dir, drives the {@link XeroBankStatementAdapter}
     * (which emits {@code bank-statement-upload-result.v1.json}), and reuses the
     * shared auth / manifest / artifact plumbing. Launches a stealth Chrome so
     * Xero's Akamai bot-manager doesn't 403 the login (Phase-0 finding).
     *
     * @param portalId      descriptor id ({@code "xero"})
     * @param request       the inbound {@code bank-statement-upload-request.v1}
     * @param extraBindings {@code params.*} runtime bindings (firmId, businessKey,
     *                      issuerRunId); supplemented with credential + runtime bindings
     */
    public RunOutcome runBankStatement(String portalId,
                                       BankStatementUploadRequest request,
                                       Map<String, String> extraBindings)
            throws IOException, InterruptedException {

        PortalDescriptor descriptor = PortalDescriptorLoader.load(portalId);
        XeroBankStatementAdapter adapter = new XeroBankStatementAdapter();

        String firmId = extraBindings.getOrDefault(
                "params.firmId", String.valueOf(request.envelope().firmId()));
        CredentialsProvider credentialsProvider = buildCredentialsProvider(descriptor, firmId);
        String credClientId = "shared".equalsIgnoreCase(descriptor.credentialScope())
                ? null : extraBindings.get("params.clientIdentifier");
        PortalCredentials credentials = credentialsProvider.get(portalId, credClientId);

        Map<String, String> bindings = buildBindings(credentials, extraBindings);
        Map<String, List<Map<String, String>>> listBindings = new HashMap<>();

        String runId = newRunId();
        Path runDir = artifactsRoot.resolve(runId);
        Files.createDirectories(runDir);
        bindings.put("runtime.runId", runId);
        bindings.put("runtime.runDir", runDir.toString());

        // Stage the request so the adapter can echo its task; decode the CSV
        // (cleartext FileBody object, or a vault: ciphertext string under KMS).
        Path requestFile = runDir.resolve("bank-statement-upload-request.v1.json");
        EnvelopeIo.write(request, requestFile);
        bindings.put(AbstractBankStatementAdapter.REQUEST_BINDING, requestFile.toString());

        EnvelopeCipher cipher = EnvelopeIo.defaultCipher();
        FileBody fileBody = request.encryption() != null && request.request() instanceof String ct
                ? EnvelopeIo.decryptBody(ct, request.encryption(), cipher, FileBody.class)
                : EnvelopeIo.MAPPER.convertValue(request.request(), FileBody.class);
        Path csvFile = runDir.resolve("statement.csv");
        Files.write(csvFile, Base64.getDecoder().decode(fileBody.file().inline()));
        bindings.put("params.source.csvPath", csvFile.toString());

        MDC.put("issuerRunId", bindings.getOrDefault("params.issuerRunId", runId));
        MDC.put("firmId", firmId);
        if (bindings.containsKey("params.businessKey")) {
            MDC.put("businessKey", bindings.get("params.businessKey"));
        }

        Optional<String> savedSession = PortalAuthService.loadSavedSession(
                SessionStores.defaultStore(), descriptor);

        RunManifest manifest = new RunManifest();
        manifest.runId = runId;
        manifest.startedAt = Instant.now();
        manifest.portal.id = descriptor.id();
        manifest.portal.baseUrl = descriptor.baseUrl();
        // Session-reuse runs need no credentials (auth via the persisted
        // session), so don't hard-require a username here.
        manifest.portal.username = credentials.values().getOrDefault("username", "(session-reuse)");
        manifest.portal.shadowMode = descriptor.isShadowMode();
        manifest.agentWorkerVersion = version(PortalRunService.class.getPackage().getImplementationVersion());
        manifest.playwrightVersion = version(Playwright.class.getPackage().getImplementationVersion());
        manifest.artifactUri = artifactStore != null ? artifactStore.urlFor(portalId, runId) : null;

        log.info("bank-statement run started portal={} sessionReused={}",
                descriptor.id(), savedSession.isPresent());

        Path manifestPath = runDir.resolve("manifest.json");
        long runStartNanos = System.nanoTime();
        String uploadedUri = null;
        try (PortalRateLimiter.Permit ignored = PortalRateLimiter.acquire(descriptor);
             Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(xeroLaunchOptions());
             BrowserContext context = browser.newContext(xeroContextOptions(runDir, savedSession))) {

            // Akamai bot-manager evasion — mask the most common automation tell
            // before any page script runs (Phase-0).
            context.addInitScript(
                    "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });");

            context.tracing().start(new Tracing.StartOptions()
                    .setScreenshots(true).setSnapshots(true).setSources(true));

            Page page = context.newPage();
            PortalEngine engine = new PortalEngine(
                    page, bindings, listBindings, manifest::step,
                    stdinOperatorInput(), descriptor.isShadowMode());

            // Auth phase — SEPARATE from the upload process the adapter runs
            // below. Xero MAY prompt for an email+password login (cold start, or
            // the session cookie has expired) or may silently re-authenticate
            // from the reused cookies (warm window). Navigate to the app first,
            // then BRANCH on whether the login form actually appeared:
            //   - form shown  -> run the login authSteps; the trust-device
            //                     cookie still skips 2FA.
            //   - form absent -> the reused session carried us straight into the
            //                     app; skip login.
            // Unconditionally filling the login form breaks the warm-session case
            // (no form is rendered -> fill times out, run parks on the homepage)
            // — observed on the first warm re-run.
            page.navigate(descriptor.baseUrl());
            boolean loginFormShown;
            try {
                page.waitForSelector(XERO_USERNAME_INPUT,
                        new Page.WaitForSelectorOptions().setTimeout(15_000));
                loginFormShown = true;
            } catch (RuntimeException alreadyAuthenticated) {
                loginFormShown = false;
            }
            if (loginFormShown) {
                engine.runSteps(descriptor.baseUrl(), descriptor.authSteps());   // navigate + fill user/pass + submit
                completeXeroLogin(page, credentials, manifest, runDir);          // optional TOTP 2FA, then wait into the app
                manifest.step("auth", "logged in via authSteps for portal=" + descriptor.id());
            } else {
                manifest.step("auth", "session reuse — already authenticated, login skipped");
            }
            manifest.portal.sessionReused = !loginFormShown;
            PortalAuthService.saveSessionIfEnabled(SessionStores.defaultStore(), descriptor, context);

            adapter.beforeSteps(descriptor, page, bindings, listBindings, credentials, manifest);
            engine.runSteps(descriptor.baseUrl(), descriptor.steps());

            manifest.step("screenshot", "report.png");
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(runDir.resolve("report.png")).setFullPage(true));

            manifest.finalUrl = page.url();
            manifest.finalTitle = page.title();
            // No descriptor scrape for Xero — the adapter builds the result body
            // and emits bank-statement-upload-result.v1.json.
            manifest.status = adapter.captureToManifest(
                    Map.of(), List.of(), bindings, credentials, manifest);

            try {
                adapter.afterCapture(page, bindings, manifest);
            } catch (RuntimeException teardownEx) {
                log.warn("afterCapture failed (non-fatal) portal={} error={}",
                        descriptor.id(), teardownEx.toString());
            }

            context.tracing().stop(new Tracing.StopOptions().setPath(runDir.resolve("trace.zip")));

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
            uploadedUri = artifactStore != null
                    ? artifactStore.uploadRunDir(runDir, portalId, runId) : null;
            log.info("bank-statement run complete status={} envelopeId={} businessKey={}",
                    manifest.status, manifest.envelopeId, manifest.businessKey);
            String terminalStatus = manifest.status != null ? manifest.status : "UNKNOWN";
            Timer.builder("agent_bankstatement_duration_seconds")
                    .tags("portal", portalId, "status", terminalStatus)
                    .register(Metrics.globalRegistry)
                    .record(System.nanoTime() - runStartNanos, TimeUnit.NANOSECONDS);
        }

        return new RunOutcome(runDir, manifest.status, null, uploadedUri);
    }

    // Xero login is fronted by Akamai Bot Manager, which 403s a vanilla
    // Playwright Chromium. Launch the installed Chrome and drop the automation
    // switches so it presents as an ordinary browser (Phase-0 confirmed).
    private static BrowserType.LaunchOptions xeroLaunchOptions() {
        BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions()
                .setHeadless(headless())
                .setIgnoreDefaultArgs(List.of("--enable-automation"))
                .setArgs(List.of("--disable-blink-features=AutomationControlled"));
        String channel = System.getProperty("xero.channel");
        if (channel == null) channel = System.getenv("XERO_BROWSER_CHANNEL");
        if (channel == null) channel = "chrome";
        if (!channel.isBlank()) {
            opts.setChannel(channel);
        }
        return opts;
    }

    /**
     * Login-only probe run. Authenticates to the portal, takes a full-page
     * screenshot, and returns without fetching any payroll data.
     *
     * <p>Triggered when {@link com.neoproc.financialagent.worker.listener.PayrollCaptureListener}
     * detects a {@code probe::} businessKey prefix. No adapter is instantiated —
     * the probe runs only the descriptor's {@code authSteps} via
     * {@link PortalAuthService#login}.
     *
     * @param portalId      portal to probe (e.g. {@code "autoplanilla"})
     * @param extraBindings must contain {@code params.firmId}; for per-client
     *                      portals (e.g. {@code ccss-sicere}) must also contain
     *                      {@code params.clientIdentifier}
     * @return outcome with {@code screenshotSha256} populated on success
     */
    public RunOutcome runProbe(String portalId, Map<String, String> extraBindings)
            throws IOException, InterruptedException {

        // Probes always use the base descriptor — authSteps are there.
        PortalDescriptor descriptor = PortalDescriptorLoader.load(portalId);

        String firmId = extraBindings.getOrDefault("params.firmId", "1");
        String clientId = extraBindings.get("params.clientIdentifier");
        CredentialsProvider credentialsProvider = buildCredentialsProvider(descriptor, firmId);
        String credClientId = "shared".equalsIgnoreCase(descriptor.credentialScope())
                ? null : clientId;
        PortalCredentials credentials = credentialsProvider.get(portalId, credClientId);

        Map<String, String> bindings = buildBindings(credentials, extraBindings);
        Map<String, List<Map<String, String>>> listBindings = new HashMap<>();

        String runId = newRunId();
        Path runDir = artifactsRoot.resolve(runId);
        Files.createDirectories(runDir);
        bindings.put("runtime.runId", runId);
        bindings.put("runtime.runDir", runDir.toString());

        MDC.put("firmId", firmId);
        if (bindings.containsKey("params.businessKey")) {
            MDC.put("businessKey", bindings.get("params.businessKey"));
        }

        RunManifest manifest = new RunManifest();
        manifest.runId = runId;
        manifest.startedAt = Instant.now();
        manifest.portal.id = descriptor.id();
        manifest.portal.baseUrl = descriptor.baseUrl();
        manifest.portal.shadowMode = descriptor.isShadowMode();
        manifest.artifactUri = artifactStore != null ? artifactStore.urlFor(portalId, runId) : null;

        log.info("probe started portal={}", portalId);

        String screenshotSha256 = null;
        String uploadedUri = null;
        Path manifestPath = runDir.resolve("manifest.json");

        try (PortalRateLimiter.Permit ignored = PortalRateLimiter.acquire(descriptor);
             Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions().setHeadless(headless()));
             BrowserContext context = browser.newContext(newContextOptions(runDir, Optional.empty()))) {

            Page page = context.newPage();

            PortalEngine engine = new PortalEngine(
                    page, bindings, listBindings, manifest::step,
                    stdinOperatorInput(), descriptor.isShadowMode());

            // Probes always log in fresh — no session reuse. PortalAuthService
            // will find no saved session (all probe portals have ttlMinutes=0)
            // and execute authSteps normally.
            PortalAuthService.login(engine, descriptor, context, page, manifest);

            Path screenshotPath = runDir.resolve("probe.png");
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath)
                    .setFullPage(true));
            manifest.step("probe-screenshot", "probe.png");

            if (Files.exists(screenshotPath) && Files.size(screenshotPath) > 0) {
                screenshotSha256 = sha256Hex(screenshotPath);
            }

            manifest.status = "SUCCESS";

        } catch (PortalEngine.ShadowHalt e) {
            manifest.status = "SHADOW_HALT";
            manifest.error = e.getMessage();
        } catch (RuntimeException e) {
            manifest.status = "FAILED";
            manifest.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            new HarScrubber(descriptor.securityContext()).scrub(runDir.resolve("network.har"));
            manifest.finishedAt = Instant.now();
            EnvelopeIo.MAPPER.writeValue(manifestPath.toFile(), manifest);
            uploadedUri = artifactStore != null
                    ? artifactStore.uploadRunDir(runDir, portalId, runId)
                    : null;
            log.info("probe complete status={} portal={} screenshotSha256={}",
                    manifest.status, portalId, screenshotSha256);
            MDC.clear();
        }

        return new RunOutcome(runDir, manifest.status, screenshotSha256, uploadedUri);
    }

    // --- adapter registry ---------------------------------------------------

    static PortalAdapter newAdapter(String portalId, boolean isCapture) {
        return switch (portalId) {
            case "mock-portal"    -> new MockPortalAdapter();
            case "mock-payroll"   -> isCapture ? new MockPayrollCaptureAdapter() : new MockPayrollAdapter();
            case "autoplanilla"   -> new AutoplanillaAdapter();
            case "ccss-sicere"    -> isCapture ? new CcssSicereCaptureAdapter() : new CcssSicereSubmitAdapter();
            case "ins-rt-virtual" -> new InsRtVirtualSubmitAdapter();
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

    // Xero/Akamai treats a bare context as suspicious and bounces it to the
    // login form even with a valid reused session (seen on the first live run).
    // Match the Phase-0 spike's context — realistic locale/timezone/viewport —
    // so the reused session silently re-authenticates.
    private static Browser.NewContextOptions xeroContextOptions(Path runDir, Optional<String> savedSession) {
        return newContextOptions(runDir, savedSession)
                .setLocale("en-US")
                .setTimezoneId("America/Costa_Rica")
                .setViewportSize(1366, 768);
    }

    /** True once the page has left Xero's login/identity host (i.e. we're in the app). */
    private static boolean offLogin(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return !u.contains("/identity/") && !u.contains("login.xero.com");
    }

    /**
     * Completes a Xero credential login that may or may not be followed by a 2FA
     * challenge, and blocks until we're in the app (or throws).
     *
     * <p>After username+password submit Xero either redirects straight into the
     * app (the persisted trust-device cookie is still valid → 2FA skipped) or
     * holds us on {@code login.xero.com} with a TOTP prompt (the ~30-day
     * trust-device cookie has expired/been revoked). This handles the latter
     * autonomously: compute the TOTP from the {@code totpSeed} credential, fill
     * it, check "trust this device" (re-minting the cookie so 2FA stays rare),
     * and submit. The first real challenge is dumped to the run dir so the
     * candidate selectors can be hardened against the live DOM.
     *
     * @throws IllegalStateException if a 2FA challenge appears but no totpSeed is
     *                               configured (operator must re-seed / add the seed)
     */
    private static void completeXeroLogin(Page page, PortalCredentials credentials,
                                          RunManifest manifest, Path runDir) {
        // Fast path: trust cookie still valid → straight into the app, no 2FA.
        try {
            page.waitForURL(PortalRunService::offLogin,
                    new Page.WaitForURLOptions().setTimeout(12_000));
            return;
        } catch (RuntimeException stillOnLogin) {
            // still on the login host after the password step — likely a TOTP challenge
        }

        Locator code = page.locator(XERO_2FA_CODE_INPUT);
        boolean challenge;
        try {
            code.first().waitFor(new Locator.WaitForOptions().setTimeout(8_000));
            challenge = true;
        } catch (RuntimeException notShown) {
            challenge = false;
        }

        if (challenge) {
            dumpDebug(page, runDir, "2fa-challenge");   // capture the live DOM to harden selectors
            String seed = credentials.values().get("totpSeed");
            if (seed == null || seed.isBlank()) {
                throw new IllegalStateException(
                        "Xero presented a 2FA challenge but no totpSeed is configured — the "
                                + "trust-device cookie has expired. Add totpSeed to the xero credential "
                                + "secret or re-seed the session via SessionSeeder.");
            }
            // Generate at fill time to minimise the 30s-window boundary risk.
            code.first().fill(TotpGenerator.now(seed));
            Locator trust = page.locator(XERO_2FA_TRUST_CHECKBOX);
            if (trust.count() > 0) {
                try {
                    trust.first().check(new Locator.CheckOptions().setTimeout(3_000));
                } catch (RuntimeException ignore) {
                    // best-effort: if we can't tick "trust this device", 2FA just recurs sooner
                }
            }
            page.locator(XERO_2FA_CONFIRM_BUTTON).first().click();
            manifest.step("auth", "submitted TOTP 2FA + trusted device (trust-device cookie had expired)");
        } else {
            // Neither in the app nor a recognised 2FA screen — capture for diagnosis;
            // the wait below will throw if we genuinely never reach the app.
            dumpDebug(page, runDir, "post-login-unrecognised");
        }

        // Block until we're in the app (post-2FA redirect or a slow login).
        page.waitForURL(PortalRunService::offLogin,
                new Page.WaitForURLOptions().setTimeout(60_000));
    }

    /** Best-effort HTML + screenshot dump to the run dir for post-mortem of an auth hang. */
    private static void dumpDebug(Page page, Path runDir, String name) {
        if (runDir == null) {
            return;
        }
        try {
            Files.writeString(runDir.resolve(name + ".html"), page.content());
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(runDir.resolve(name + ".png")).setFullPage(true));
        } catch (Exception ignore) {
            // diagnostics are best-effort; never mask the original failure
        }
    }

    private static String sha256Hex(Path path) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
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

    // Visible Chromium for local debugging. Defaults to true so containerised
    // and CI runs stay headless; set -Dportal.headless=false (or env
    // PORTAL_HEADLESS=false) to watch the run in a window.
    private static boolean headless() {
        String prop = System.getProperty("portal.headless");
        if (prop == null) prop = System.getenv("PORTAL_HEADLESS");
        return prop == null || !prop.equalsIgnoreCase("false");
    }
}
