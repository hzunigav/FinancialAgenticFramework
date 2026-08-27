package com.neoproc.financialagent.worker.demo;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.neoproc.financialagent.worker.auth.ImapOtpMailboxReader;
import com.neoproc.financialagent.worker.auth.ManualOtpMailboxReader;
import com.neoproc.financialagent.worker.auth.OtpMailboxReader;
import com.neoproc.financialagent.worker.portal.PortalDescriptor;
import com.neoproc.financialagent.worker.portal.PortalDescriptorLoader;
import com.neoproc.financialagent.worker.portal.PortalEngine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Headed dry-run of the REAL hacienda-ovi login recipe — drives the descriptor's
 * {@code authSteps} through {@link PortalEngine} exactly as the worker will,
 * including the generic {@code emailOtp} action. This is the login-portion
 * equivalent of the INS/Xero headed dry-runs: prove the declarative login works
 * end-to-end before a business flow (or a service mailbox) exists.
 *
 * <p>OTP source:
 * <ul>
 *   <li><b>Manual (default)</b> — no mailbox creds needed: the engine reaches the
 *       {@code emailOtp} step and prompts you to type the code from your inbox.
 *       Validates every selector + the flow without provisioning a mailbox.</li>
 *   <li><b>IMAP (automated)</b> — pass {@code -Dhacienda.imap.host=...} etc. and
 *       the run retrieves the code itself via {@link ImapOtpMailboxReader},
 *       exercising the full unattended path (e.g. Gmail: imap.gmail.com:993 with
 *       an app-password).</li>
 * </ul>
 *
 * <p>Run (headed):
 * <pre>
 * mvn.cmd -pl agent-worker exec:java -q \
 *   -Dexec.mainClass=com.neoproc.financialagent.worker.demo.HaciendaLoginDryRun \
 *   -Dexec.args="hacienda-dryrun" \
 *   -Dhacienda.username=CEDULA -Dhacienda.password=SECRET
 * # optional automated OTP:
 * #   -Dhacienda.imap.host=imap.gmail.com -Dhacienda.imap.port=993 \
 * #   -Dhacienda.imap.user=inbox@gmail.com -Dhacienda.imap.pass=APP_PASSWORD
 * </pre>
 * On success the login card disappears (the descriptor's final wait) and the
 * refreshed {@code storageState.json} is written to the out dir for the session
 * store. Override the channel with {@code -Dhacienda.channel=} (blank → bundled
 * Chromium).
 */
public final class HaciendaLoginDryRun {

    private HaciendaLoginDryRun() {}

    public static void main(String[] args) throws IOException {
        Path outDir = Path.of(args.length > 0 ? args[0] : "hacienda-dryrun");
        Files.createDirectories(outDir);

        String username = required("hacienda.username");
        String password = required("hacienda.password");

        BufferedReader stdin = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        OtpMailboxReader otpReader = buildOtpReader(stdin);

        PortalDescriptor descriptor;
        try {
            descriptor = PortalDescriptorLoader.load("hacienda-ovi");
        } catch (IOException e) {
            throw new IllegalStateException("Could not load hacienda-ovi descriptor", e);
        }

        String channel = System.getProperty("hacienda.channel", "chrome");
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(false)
                .setIgnoreDefaultArgs(List.of("--enable-automation"))
                .setArgs(List.of("--disable-blink-features=AutomationControlled"));
        if (channel != null && !channel.isBlank()) {
            launchOptions.setChannel(channel);
        }

        Map<String, String> bindings = new HashMap<>();
        bindings.put("credentials.username", username);
        bindings.put("credentials.password", password);

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(launchOptions)) {

            Browser.NewContextOptions ctxOptions = new Browser.NewContextOptions()
                    .setLocale("es-CR")
                    .setTimezoneId("America/Costa_Rica")
                    .setViewportSize(1366, 768);
            try (BrowserContext context = browser.newContext(ctxOptions)) {
                context.addInitScript(
                        "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });");
                Page page = context.newPage();

                PortalEngine engine = new PortalEngine(
                        page, bindings,
                        (action, target) -> System.out.println("  [" + action + "] " + target),
                        prompt -> promptStdin(stdin, prompt),
                        false)
                        .withOtpMailbox(otpReader);

                System.out.println();
                System.out.println("=== Hacienda OVi login dry-run (real authSteps) ===");
                System.out.println("Driving " + descriptor.authSteps().size() + " authSteps against "
                        + descriptor.baseUrl());

                try {
                    engine.runSteps(descriptor.baseUrl(), descriptor.authSteps());
                    System.out.println();
                    System.out.println("LOGIN OK — landed at: " + page.url());
                    Files.writeString(outDir.resolve("storageState.json"), context.storageState());
                    page.screenshot(new Page.ScreenshotOptions()
                            .setPath(outDir.resolve("login-ok.png")).setFullPage(true));
                    System.out.println("Saved storageState.json + login-ok.png to " + outDir.toAbsolutePath());
                } catch (RuntimeException failed) {
                    System.out.println();
                    System.out.println("LOGIN FAILED at URL: " + page.url());
                    System.out.println("  " + failed.getClass().getSimpleName() + ": " + failed.getMessage());
                    Files.writeString(outDir.resolve("login-failed.html"), page.content());
                    page.screenshot(new Page.ScreenshotOptions()
                            .setPath(outDir.resolve("login-failed.png")).setFullPage(true));
                    System.out.println("Dumped login-failed.html + .png to " + outDir.toAbsolutePath());
                }

                System.out.println();
                System.out.print(">>> Press Enter to close the browser... ");
                System.out.flush();
                stdin.readLine();
            }
        }
    }

    /** IMAP reader when host is configured, else a manual stdin reader. */
    private static OtpMailboxReader buildOtpReader(BufferedReader stdin) {
        String host = System.getProperty("hacienda.imap.host");
        if (host != null && !host.isBlank()) {
            int port = Integer.parseInt(System.getProperty("hacienda.imap.port", "993"));
            String user = required("hacienda.imap.user");
            String pass = required("hacienda.imap.pass");
            String folder = System.getProperty("hacienda.imap.folder", "INBOX");
            System.out.println("OTP source: IMAP " + host + ":" + port + " (automated)");
            return new ImapOtpMailboxReader(
                    new ImapOtpMailboxReader.ImapConfig(host, port, user, pass, folder));
        }
        System.out.println("OTP source: MANUAL — you will be prompted to type the emailed code");
        return new ManualOtpMailboxReader(msg -> promptStdin(stdin, msg));
    }

    private static String promptStdin(BufferedReader stdin, String message) {
        System.out.println();
        System.out.print(">>> " + message + ": ");
        System.out.flush();
        try {
            String line = stdin.readLine();
            return line != null ? line.trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String required(String prop) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required -D" + prop);
        }
        return v;
    }
}
