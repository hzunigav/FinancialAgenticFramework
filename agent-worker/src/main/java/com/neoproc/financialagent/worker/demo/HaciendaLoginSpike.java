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
 * Phase-0 Hacienda OVI spike — NOT production. Drives a headed Chromium against
 * {@code ovitribucr.hacienda.go.cr} so the OVI portal can be de-risked before
 * any adapter or the generic {@code emailOtp} engine action is written.
 *
 * <p>Unlike Xero, OVI's second factor is a <b>server-generated OTP delivered by
 * email or SMS</b> — there is no TOTP seed we can hold, so the worker must
 * <i>retrieve</i> the code out-of-band (see the recommendation thread). This
 * spike answers the four facts that gate that design, in one manual run:
 *
 * <ol>
 *   <li><b>Login + OTP-challenge DOM</b> — dumps {@code page.content()} HTML +
 *       screenshots of (a) the login form (Identificación / Contraseña / the
 *       "Por correo electrónico" radio / Entrar) and (b) the <i>code-entry</i>
 *       screen that appears after Entrar. These are the selectors the
 *       descriptor authSteps + the {@code emailOtp} action will bind to.</li>
 *   <li><b>Which 2FA channels the account offers</b> — you observe whether
 *       "Por correo electrónico" is actually available (vs SMS-locked) while
 *       driving the form by hand.</li>
 *   <li><b>OTP email format</b> — you paste the received mail's sender, subject
 *       and the line containing the code; the spike saves it to
 *       {@code otp-email.txt} so the retrieval regex is designed against a real
 *       sample (never a guess), plus the observed expiry window.</li>
 *   <li><b>Session survival</b> — saves {@code storageState()}, reports cookie /
 *       localStorage / sessionStorage key counts, then re-seeds a <i>fresh</i>
 *       context with only that storageState and reloads the home page. If it
 *       lands authenticated, the Secrets-Manager session store lets us reuse a
 *       session and hit 2FA rarely; if it bounces to login, OVI keeps auth in
 *       sessionStorage/JWT (like AutoPlanilla/INS) and every run re-logs in.</li>
 * </ol>
 *
 * <p>Run (headed; forwards stdin for the Enter prompts):
 * <pre>
 * mvn.cmd -pl agent-worker exec:java -q \
 *   -Dexec.mainClass=com.neoproc.financialagent.worker.demo.HaciendaLoginSpike \
 *   -Dexec.args="hacienda-spike"
 * </pre>
 * Optional overrides: {@code -Dhacienda.url=...} (login start, default
 * {@code https://ovitribucr.hacienda.go.cr/home}) and {@code -Dhacienda.channel=}
 * (blank to fall back to bundled Chromium instead of installed Chrome).
 *
 * <p>Hand the resulting {@code <outDir>/} back for analysis — especially
 * {@code 00-login-form.html}, {@code 01-otp-challenge.html},
 * {@code otp-email.txt} and {@code storage-summary.txt}.
 */
public final class HaciendaLoginSpike {

    private HaciendaLoginSpike() {}

    /** Post-login screens to snapshot, in order; you navigate to each, then press Enter. */
    private static final List<String> SCREENS = List.of(
            "the authenticated OVi home / landing page (logged in)",
            "the main Menú expanded (so we see the nav structure)");

    public static void main(String[] args) throws IOException {
        Path outDir = Path.of(args.length > 0 ? args[0] : "hacienda-spike");
        Files.createDirectories(outDir);
        String startUrl = System.getProperty("hacienda.url",
                "https://ovitribucr.hacienda.go.cr/home");

        BufferedReader stdin = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        // Present as a real Chrome install by default (drop --enable-automation,
        // disable the AutomationControlled blink feature, mask navigator.webdriver
        // below). Unknown whether OVI runs edge bot-protection; the stealth launch
        // is a safe default. Override with -Dhacienda.channel= (blank) to use the
        // bundled Chromium instead of installed Google Chrome.
        String channel = System.getProperty("hacienda.channel", "chrome");
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
            // previously-captured storageState silently re-authenticates. No 2FA
            // needed — repeatable.
            //   -Dhacienda.reuseStateFile=hacienda-spike/storageState.json
            String reuseFile = System.getProperty("hacienda.reuseStateFile");
            if (reuseFile != null && !reuseFile.isBlank()) {
                String saved = Files.readString(Path.of(reuseFile));
                ReuseVerdict v = runReuseProbe(browser, saved, startUrl, outDir);
                System.out.printf("%nREUSE-ONLY probe (state=%s)%n  landed at      : %s%n  looks logged in: %s%n",
                        reuseFile, v.url(), v.loggedIn());
                System.out.println("See " + outDir.resolve("02-reused-session.png")
                        + " for the settled page.");
                return;
            }

            BrowserContext context = newStealthContext(browser, null);
            Page page = context.newPage();
            page.navigate(startUrl);

            // Capture the login-form DOM BEFORE you log in — these are the
            // Identificación / Contraseña / channel-radio / Entrar selectors the
            // descriptor authSteps need.
            try {
                page.waitForSelector("input", new Page.WaitForSelectorOptions().setTimeout(15_000));
            } catch (RuntimeException ignore) { /* capture whatever rendered */ }
            capture(page, outDir, "00-login-form");

            System.out.println();
            System.out.println("=== Hacienda OVI Phase-0 spike ===");
            System.out.println("A browser window is open at: " + startUrl);
            System.out.println();
            System.out.println("STEP 1 — Fill Identificación + Contraseña, choose the 2FA channel,");
            System.out.println("         and click Entrar. NOTE which channels are offered:");
            System.out.println("         is 'Por correo electrónico' selectable, or is it SMS-only?");
            prompt(stdin, "Once the OTP code-entry screen is showing (do NOT enter the code yet), press Enter to capture it...");

            // THE key new artifact vs Xero: the code-entry screen DOM. This is
            // where the generic emailOtp action will type the retrieved code.
            capture(page, outDir, "01-otp-challenge");

            // Record what channels were offered + the OTP email format, by hand.
            // The spike has no mailbox creds wired yet, so you transcribe the mail
            // it actually sent — this is the sample the retrieval regex is built
            // against (sender/subject/code line) and the expiry window we design
            // the poll timeout around.
            String channelsOffered = promptValue(stdin,
                    "Which 2FA channels did OVI offer? (e.g. 'email + SMS', 'SMS only')");
            String emailSender = promptValue(stdin,
                    "OTP email — From address (blank if you used SMS)");
            String emailSubject = promptValue(stdin,
                    "OTP email — Subject line");
            String emailCodeLine = promptValue(stdin,
                    "OTP email — paste the exact sentence/line that contains the code");
            String expiryNote = promptValue(stdin,
                    "How long is the code valid / any 're-send' option? (e.g. '5 min, Reenviar link')");
            Files.writeString(outDir.resolve("otp-email.txt"), """
                    Hacienda OVI — 2FA / OTP sample (transcribed by hand during Phase-0 spike)
                    ------------------------------------------------------------------------
                    channels offered : %s
                    email From        : %s
                    email Subject     : %s
                    code line (raw)   : %s
                    validity / resend : %s

                    Use the code line above to design the emailOtp extraction regex, and the
                    validity window to size the mailbox poll timeout. If SMS-only, the email
                    auto-retrieval path is not available for this account — see the thread.
                    """.formatted(channelsOffered, emailSender, emailSubject, emailCodeLine, expiryNote));

            prompt(stdin, "Now enter the OTP, finish logging in, wait for the authenticated home, then press Enter...");

            // Session-survival probe -------------------------------------------
            String storageState = context.storageState();
            Files.writeString(outDir.resolve("storageState.json"), storageState);

            int cookieCount = context.cookies().size();
            long localCount = countKeys(page, "localStorage");
            long sessionCount = countKeys(page, "sessionStorage");
            String afterLoginUrl = page.url();

            capture(page, outDir, "01b-after-login");

            // Fresh context seeded ONLY with storageState (no sessionStorage) —
            // exactly what PortalAuthService session reuse replays.
            ReuseVerdict verdict = runReuseProbe(browser, storageState, startUrl, outDir);

            String summary = """
                    Hacienda OVI Phase-0 session-survival probe
                    -------------------------------------------
                    url after login   : %s
                    cookies            : %d
                    localStorage keys  : %d
                    sessionStorage keys: %d

                    storageState reuse test (fresh context, sessionStorage NOT carried):
                    landed at          : %s
                    looks logged in    : %s

                    Interpretation:
                    - looks logged in = true  -> persisted storageState is enough; the
                      Secrets-Manager session store lets us reuse a session and hit 2FA
                      rarely (only on cold seed / ttl lapse).
                    - looks logged in = false -> auth likely lives in sessionStorage/JWT
                      (like AutoPlanilla/INS, ttlMinutes=0); every run re-logs in and MUST
                      retrieve a fresh OTP, so the emailOtp path is on the hot path.
                    - sessionStorage keys high + cookies low is a strong tell of the
                      JWT-in-sessionStorage pattern.
                    """.formatted(afterLoginUrl, cookieCount, localCount, sessionCount,
                    verdict.url(), verdict.loggedIn());
            Files.writeString(outDir.resolve("storage-summary.txt"), summary);
            System.out.println();
            System.out.println(summary);

            // Guided post-login selector capture -------------------------------
            for (int i = 0; i < SCREENS.size(); i++) {
                prompt(stdin, "Navigate to " + SCREENS.get(i) + ", then press Enter to capture...");
                String name = String.format("%02d-%s", i + 3,
                        SCREENS.get(i).replaceAll("[^a-zA-Z0-9]+", "-"));
                capture(page, outDir, name);
            }

            System.out.println();
            System.out.println("Done. Artifacts written to: " + outDir.toAbsolutePath());
            System.out.println("Send back 00-login-form.html, 01-otp-challenge.html, otp-email.txt");
            System.out.println("and storage-summary.txt for analysis.");
        }
    }

    /** Outcome of a session-reuse probe. */
    private record ReuseVerdict(String url, boolean loggedIn) {}

    /**
     * Opens a fresh stealth context seeded only with {@code storageState}
     * (no sessionStorage — exactly what PortalAuthService session reuse
     * replays), navigates to the home page, and judges whether the session
     * survived. "Logged in" is inferred from the URL no longer sitting on the
     * public /home login route AND a login input no longer being present —
     * refine once we know OVI's authenticated URL shape from the first run.
     */
    private static ReuseVerdict runReuseProbe(Browser browser, String storageState,
                                              String homeUrl, Path outDir) throws IOException {
        try (BrowserContext reused = newStealthContext(browser, storageState)) {
            Page page = reused.newPage();
            page.navigate(homeUrl);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            try {
                page.waitForTimeout(3_000);   // let any client-side auth redirect settle
            } catch (RuntimeException ignore) { /* best effort */ }
            String url = page.url();
            // Heuristic: the login form shows a password field. If none is present
            // after settling, we very likely re-authenticated silently.
            boolean passwordVisible = page.locator("input[type='password']").count() > 0;
            boolean loggedIn = !passwordVisible;
            capture(page, outDir, "02-reused-session");
            return new ReuseVerdict(url, loggedIn);
        }
    }

    /**
     * Creates a context that looks like an ordinary Chrome session: realistic
     * locale/timezone/viewport and {@code navigator.webdriver} masked via an
     * init script that runs before any page script. Optionally seeded with a
     * prior {@code storageState} (used by the reuse test).
     */
    private static BrowserContext newStealthContext(Browser browser, String storageState) {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setLocale("es-CR")
                .setTimezoneId("America/Costa_Rica")
                .setViewportSize(1366, 768);
        if (storageState != null) {
            options.setStorageState(storageState);
        }
        BrowserContext context = browser.newContext(options);
        context.addInitScript(
                "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });");
        return context;
    }

    private static long countKeys(Page page, String store) {
        try {
            Object n = page.evaluate("() => Object.keys(" + store + ").length");
            return n instanceof Number num ? num.longValue() : -1;
        } catch (RuntimeException e) {
            return -1;   // SecurityError on some origins — report as unknown
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

    /** Prompts for a free-text value, echoing it back into a saved artifact. */
    private static String promptValue(BufferedReader stdin, String label) throws IOException {
        System.out.println();
        System.out.print(">>> " + label + ": ");
        System.out.flush();
        String line = stdin.readLine();
        return line != null ? line.trim() : "";
    }
}
