package com.neoproc.financialagent.worker.demo;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Phase-2e infra probe — answers the "headless Akamai on Fargate" question
 * WITHOUT a deploy. Launches the same stealth browser the Xero adapter uses,
 * navigates COLD (no session) to {@code login.xero.com}, and reports whether
 * Akamai served the login form (PASS) or an Access-Denied block (FAIL).
 *
 * <p>Two variables to isolate, both testable locally:
 * <ol>
 *   <li><b>Headless mode</b> — run locally with {@code -Dportal.headless=true}.
 *       If this FAILs but headed passes, headless itself trips Akamai.</li>
 *   <li><b>The runtime image's browser</b> — run this class INSIDE the
 *       {@code mcr.microsoft.com/playwright/java} image (same as Fargate). The
 *       image ships Chromium, not branded Chrome; if {@code channel=chrome}
 *       can't launch, the probe auto-retries with bundled Chromium and reports
 *       whether THAT clears Akamai.</li>
 * </ol>
 *
 * <p>Run locally (headless, branded Chrome):
 * <pre>
 * java -Dportal.headless=true -cp "agent-worker\target\classes;$cp" \
 *   com.neoproc.financialagent.worker.demo.XeroAkamaiProbe
 * </pre>
 *
 * <p>Run inside the runtime image (true Fargate fidelity):
 * <pre>
 * docker build -t financeagent-worker:probe .
 * docker run --rm -v "$PWD/artifacts:/app/artifacts" \
 *   -e PORTAL_HEADLESS=true -e XERO_BROWSER_CHANNEL=chrome \
 *   --entrypoint java financeagent-worker:probe \
 *   -cp /app/app.jar com.neoproc.financialagent.worker.demo.XeroAkamaiProbe
 * </pre>
 * Set {@code -e XERO_BROWSER_CHANNEL=} (empty) to force bundled Chromium.
 *
 * <p>Exit code 0 = PASS (login form rendered), 1 = FAIL/blocked. The screenshot
 * + HTML land in {@code artifacts/akamai-probe/} for inspection.
 */
public final class XeroAkamaiProbe {

    private static final String LOGIN_URL = "https://login.xero.com/identity/user/login";
    private static final String USERNAME_INPUT = "[data-automationid='Username--input']";

    private XeroAkamaiProbe() {}

    public static void main(String[] args) throws Exception {
        String channel = firstNonBlank(
                System.getProperty("xero.channel"),
                System.getenv("XERO_BROWSER_CHANNEL"),
                "chrome");
        boolean headless = !"false".equalsIgnoreCase(firstNonBlank(
                System.getProperty("portal.headless"),
                System.getenv("PORTAL_HEADLESS"),
                "true"));

        System.out.printf("%n=== Xero Akamai probe ===%n channel=%s headless=%s url=%s%n%n",
                channel.isBlank() ? "(bundled chromium)" : channel, headless, LOGIN_URL);

        boolean pass = attempt(channel, headless);
        if (!pass && !channel.isBlank()) {
            System.out.printf("%n--- retrying with bundled Chromium (channel cleared) ---%n");
            pass = attempt("", headless);
        }

        System.out.printf("%n=== RESULT: %s ===%n", pass ? "PASS — login form rendered, Akamai cleared"
                : "FAIL — Akamai blocked or login form never appeared");
        System.exit(pass ? 0 : 1);
    }

    private static boolean attempt(String channel, boolean headless) {
        BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setIgnoreDefaultArgs(List.of("--enable-automation"))
                .setArgs(List.of("--disable-blink-features=AutomationControlled"));
        if (!channel.isBlank()) {
            launch.setChannel(channel);
        }

        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(launch);
             BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                     .setLocale("en-US").setTimezoneId("America/Costa_Rica").setViewportSize(1366, 768))) {

            ctx.addInitScript("Object.defineProperty(navigator, 'webdriver', { get: () => undefined });");
            Page page = ctx.newPage();
            page.navigate(LOGIN_URL, new Page.NavigateOptions().setTimeout(45_000));

            boolean loginForm;
            try {
                page.waitForSelector(USERNAME_INPUT,
                        new Page.WaitForSelectorOptions().setTimeout(15_000));
                loginForm = true;
            } catch (RuntimeException notShown) {
                loginForm = false;
            }

            String body = safeBody(page);
            boolean blocked = body.contains("Access Denied")
                    || body.contains("Reference #")
                    || body.toLowerCase().contains("edgesuite")
                    || body.toLowerCase().contains("akamai");

            dump(page, channel);
            System.out.printf("  channel=%-18s url=%s%n  title=%s%n  loginForm=%s blocked=%s%n",
                    channel.isBlank() ? "(chromium)" : channel, page.url(), page.title(), loginForm, blocked);
            return loginForm && !blocked;

        } catch (RuntimeException launchOrNav) {
            System.out.printf("  channel=%-18s LAUNCH/NAV FAILED: %s%n",
                    channel.isBlank() ? "(chromium)" : channel, launchOrNav.getMessage());
            return false;
        }
    }

    private static String safeBody(Page page) {
        try {
            String t = page.locator("body").textContent();
            return t != null ? t : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static void dump(Page page, String channel) {
        try {
            Path dir = Path.of(System.getProperty("artifacts.dir", "artifacts"), "akamai-probe");
            Files.createDirectories(dir);
            String tag = channel.isBlank() ? "chromium" : channel;
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(dir.resolve("probe-" + tag + ".png")).setFullPage(true));
            Files.writeString(dir.resolve("probe-" + tag + ".html"), page.content());
            System.out.println("  dumped probe-" + tag + ".{png,html} to " + dir);
        } catch (Exception ignore) {
            // diagnostics best-effort
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}
