package com.neoproc.financialagent.worker.demo;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Phase-0 Xero spike — NOT production. Drives a headed Chromium against
 * Xero so the BankRecon agent half can be de-risked before any adapter is
 * written. You complete the login + 2FA <b>manually</b> in the window (no
 * TOTP seed needed yet); the spike then captures everything the
 * implementation plan's Phase 0 needs in one run:
 *
 * <ol>
 *   <li><b>Session-survival probe</b> — saves {@code context.storageState()}
 *       and reports cookie / localStorage / sessionStorage key counts, then
 *       opens a <i>fresh</i> context seeded only with that storageState and
 *       navigates to the dashboard. If it lands logged-in, the persistent
 *       Secrets-Manager session store works as-is; if it bounces to login,
 *       Xero keeps auth in sessionStorage/JWT (like AutoPlanilla/INS) and we
 *       need a different persisted artifact / the warm-worker fallback.</li>
 *   <li><b>Selector fixtures</b> — dumps {@code page.content()} HTML +
 *       full-page screenshots of the org switcher, Accounting &rarr; Bank
 *       accounts, and the Import a Statement screen, so the descriptor
 *       authSteps + adapter selectors are built from real DOM (and we can
 *       answer Q2: is the account number / IBAN visible on the import
 *       screen?).</li>
 * </ol>
 *
 * <p>Run (headed; forwards stdin for the Enter prompts):
 * <pre>
 * mvn.cmd -pl agent-worker exec:java -q \
 *   -Dexec.mainClass=com.neoproc.financialagent.worker.demo.XeroLoginSpike \
 *   -Dexec.args="xero-spike"
 * </pre>
 * Optional overrides: {@code -Dxero.url=...} (login start, default
 * {@code https://login.xero.com/}) and {@code -Dxero.dashboard=...}
 * (post-login landing for the reuse test, default
 * {@code https://go.xero.com/Dashboard/}).
 *
 * <p>Hand the resulting {@code <outDir>/} back for analysis — especially
 * {@code storage-summary.txt} and the {@code *.html} snapshots.
 */
public final class XeroLoginSpike {

    private XeroLoginSpike() {}

    /** Screens to snapshot, in order; you navigate to each, then press Enter. */
    private static final List<String> SCREENS = List.of(
            "the org switcher (open the org menu / org-list)",
            "Accounting > Bank accounts (the account list)",
            "the bank account's 'Import a Statement' screen");

    public static void main(String[] args) throws IOException {
        Path outDir = Path.of(args.length > 0 ? args[0] : "xero-spike");
        Files.createDirectories(outDir);
        String startUrl = System.getProperty("xero.url", "https://login.xero.com/");
        String dashboardUrl = System.getProperty("xero.dashboard", "https://go.xero.com/Dashboard/");

        BufferedReader stdin = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        // Xero fronts its login with Akamai Bot Manager, which 403s a vanilla
        // Playwright Chromium at the edge. Present as a real Chrome install:
        // drop the --enable-automation switch, disable the AutomationControlled
        // blink feature, and (below) mask navigator.webdriver + use a realistic
        // context. channel="chrome" uses the installed Google Chrome; override
        // with -Dxero.channel= (blank) to fall back to bundled Chromium.
        String channel = System.getProperty("xero.channel", "chrome");
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(false)
                .setIgnoreDefaultArgs(List.of("--enable-automation"))
                .setArgs(List.of("--disable-blink-features=AutomationControlled"));
        if (channel != null && !channel.isBlank()) {
            launchOptions.setChannel(channel);
        }

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(launchOptions)) {

            // Reuse-only mode: skip the manual login and test whether a
            // previously-captured storageState (e.g. a "Trust this device"
            // run) silently re-authenticates. No 2FA needed — repeatable.
            //   -Dxero.reuseStateFile=xero-spike/storageState.json
            String reuseFile = System.getProperty("xero.reuseStateFile");
            if (reuseFile != null && !reuseFile.isBlank()) {
                String saved = Files.readString(Path.of(reuseFile));
                ReuseVerdict v = runReuseProbe(browser, saved, dashboardUrl, outDir);
                System.out.printf("%nREUSE-ONLY probe (state=%s)%n  landed at      : %s%n  looks logged in: %s%n",
                        reuseFile, v.url(), v.loggedIn());
                System.out.println("See " + outDir.resolve("02-reused-session.png")
                        + " for the settled page.");
                return;
            }

            BrowserContext context = newStealthContext(browser, null);
            Page page = context.newPage();
            page.navigate(startUrl);

            System.out.println();
            System.out.println("=== Xero Phase-0 spike ===");
            System.out.println("A browser window is open at: " + startUrl);
            prompt(stdin, "Log in + complete 2FA manually, wait for the dashboard, then press Enter...");

            // 1) Session-survival probe ----------------------------------------
            String storageState = context.storageState();
            Files.writeString(outDir.resolve("storageState.json"), storageState);

            int cookieCount = context.cookies().size();
            long localCount = countKeys(page, "localStorage");
            long sessionCount = countKeys(page, "sessionStorage");
            String afterLoginUrl = page.url();

            capture(page, outDir, "01-after-login");

            // Fresh context seeded ONLY with storageState (no sessionStorage) —
            // this is exactly what PortalAuthService session reuse replays.
            ReuseVerdict verdict = runReuseProbe(browser, storageState, dashboardUrl, outDir);
            String reuseUrl = verdict.url();
            boolean looksLoggedIn = verdict.loggedIn();

            String summary = """
                    Xero Phase-0 session-survival probe
                    -----------------------------------
                    url after login   : %s
                    cookies            : %d
                    localStorage keys  : %d
                    sessionStorage keys: %d

                    storageState reuse test (fresh context, sessionStorage NOT carried):
                    landed at          : %s
                    looks logged in    : %s

                    Interpretation:
                    - looks logged in = true  -> persisted storageState is enough; the
                      Secrets-Manager session store works as-is.
                    - looks logged in = false -> auth likely lives in sessionStorage/JWT
                      (like AutoPlanilla/INS, ttlMinutes=0); persist a trusted-device
                      cookie or use the warm-worker fallback (plan section 1.5).
                    - sessionStorage keys high + cookies low is a strong tell of the
                      JWT-in-sessionStorage pattern.
                    """.formatted(afterLoginUrl, cookieCount, localCount, sessionCount,
                    reuseUrl, looksLoggedIn);
            Files.writeString(outDir.resolve("storage-summary.txt"), summary);
            System.out.println();
            System.out.println(summary);

            // 2) Guided selector-fixture capture -------------------------------
            for (int i = 0; i < SCREENS.size(); i++) {
                prompt(stdin, "Navigate to " + SCREENS.get(i) + ", then press Enter to capture...");
                String name = String.format("%02d-%s", i + 3,
                        SCREENS.get(i).replaceAll("[^a-zA-Z0-9]+", "-"));
                capture(page, outDir, name);
            }

            System.out.println();
            System.out.println("Done. Artifacts written to: " + outDir.toAbsolutePath());
            System.out.println("Send back storage-summary.txt and the *.html snapshots for analysis.");
        }
    }

    /** Outcome of a session-reuse probe. */
    private record ReuseVerdict(String url, boolean loggedIn) {}

    /**
     * Opens a fresh stealth context seeded only with {@code storageState}
     * (no sessionStorage — exactly what PortalAuthService session reuse
     * replays), navigates to the dashboard, and judges whether the session
     * survived.
     *
     * <p>Xero uses an OIDC hybrid flow ({@code response_type=code id_token},
     * {@code response_mode=form_post}): the dashboard redirects through
     * {@code login.xero.com/identity/connect/authorize}, which — with a valid
     * persisted cookie — auto-submits a hidden form and bounces back to the
     * app with no prompt. We must wait for that redirect chain to LEAVE the
     * identity host before judging, or we catch the transient (blank)
     * form-post page and misread it as a login wall.
     */
    private static ReuseVerdict runReuseProbe(Browser browser, String storageState,
                                              String dashboardUrl, Path outDir) throws IOException {
        try (BrowserContext reused = newStealthContext(browser, storageState)) {
            Page page = reused.newPage();
            page.navigate(dashboardUrl);
            try {
                page.waitForURL(
                        u -> !u.contains("/identity/") && !u.toLowerCase().contains("login.xero.com"),
                        new Page.WaitForURLOptions().setTimeout(20_000));
            } catch (RuntimeException stillOnIdentityHost) {
                // Timed out on the identity host → a real login/2FA wall, not a
                // silent SSO hop. Fall through and report the (login) URL.
            }
            page.waitForLoadState(LoadState.NETWORKIDLE);
            String url = page.url();
            boolean loggedIn = !url.contains("/identity/")
                    && !url.toLowerCase().contains("login.xero.com");
            capture(page, outDir, "02-reused-session");
            return new ReuseVerdict(url, loggedIn);
        }
    }

    /**
     * Creates a context that looks like an ordinary Chrome session to Akamai:
     * realistic locale/timezone/viewport and {@code navigator.webdriver}
     * masked via an init script that runs before any page script. Optionally
     * seeded with a prior {@code storageState} (used by the reuse test).
     */
    private static BrowserContext newStealthContext(Browser browser, String storageState) {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setLocale("en-US")
                .setTimezoneId("America/Costa_Rica")
                .setViewportSize(1366, 768);
        if (storageState != null) {
            options.setStorageState(storageState);
        }
        BrowserContext context = browser.newContext(options);
        // Runs before the page's own scripts on every navigation — hides the
        // most common automation tell that Akamai's sensor checks.
        context.addInitScript(
                "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });");
        return context;
    }

    private static long countKeys(Page page, String store) {
        try {
            Object n = page.evaluate("() => Object.keys(" + store + ").length");
            return n instanceof Number num ? num.longValue() : -1;
        } catch (RuntimeException e) {
            // SecurityError on some origins — report as unknown rather than abort.
            return -1;
        }
    }

    private static void capture(Page page, Path outDir, String name) throws IOException {
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(outDir.resolve(name + ".png"))
                .setFullPage(true));
        Files.writeString(outDir.resolve(name + ".html"), page.content());
        System.out.println("captured " + name + "  @ " + page.url());
    }

    private static void prompt(BufferedReader stdin, String message) throws IOException {
        System.out.println();
        System.out.print(">>> " + message + " ");
        System.out.flush();
        stdin.readLine();
    }
}
