package com.neoproc.financialagent.worker.portal;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.neoproc.financialagent.worker.auth.OtpMailboxReader;
import com.neoproc.financialagent.worker.auth.OtpQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@code emailOtp} engine action (retrieve an out-of-band 2FA code via
 * an injected {@link OtpMailboxReader} and type it into a field) and the
 * {@code waitForSelector state=hidden} primitive that detects login completion.
 * Uses a headless page + a fake reader — no real mailbox.
 */
class PortalEngineEmailOtpTest {

    private static String htmlPage(String bodyHtml) {
        return "data:text/html,<!doctype html><html><body>" + bodyHtml + "</body></html>";
    }

    /** Fake reader: records the query it was asked for and returns a fixed code. */
    private static final class FakeReader implements OtpMailboxReader {
        final AtomicReference<OtpQuery> seen = new AtomicReference<>();
        private final String code;
        FakeReader(String code) { this.code = code; }
        @Override public String awaitCode(OtpQuery query) {
            seen.set(query);
            return code;
        }
    }

    private static PortalDescriptor.Step emailOtpStep(String selector, PortalDescriptor.EmailOtp cfg) {
        return new PortalDescriptor.Step(
                PortalDescriptor.Action.emailOtp,
                selector, null, null, null, null, null, null,
                null, null, null,
                null, null, null, null, null, null,
                null, cfg, null);
    }

    private static PortalDescriptor.Step waitHiddenStep(String selector) {
        return new PortalDescriptor.Step(
                PortalDescriptor.Action.waitForSelector,
                selector, null, null, null, null, null, null,
                null, null, null,
                null, null, null, null, null, null,
                null, null, "hidden");
    }

    private static PortalDescriptor.EmailOtp oviCfg() {
        return new PortalDescriptor.EmailOtp(
                "tribucrcorreo@hacienda.go.cr",
                "Validación de los usuarios de servicios",
                "código de validación es:\\s*(\\d{4,6})",
                5, 1);
    }

    @Test
    void emailOtp_typesRetrievedCode_andPassesFiltersToReader() {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.navigate(htmlPage("<input id='pin' maxlength='5'>"));

            FakeReader reader = new FakeReader("14528");
            List<String> audits = new ArrayList<>();
            PortalEngine engine = new PortalEngine(
                    page, new HashMap<>(),
                    (a, t) -> audits.add(a + ":" + t),
                    p -> "", false)
                    .withOtpMailbox(reader);

            engine.runSteps("http://example", List.of(emailOtpStep("#pin", oviCfg())));

            assertEquals("14528", page.locator("#pin").inputValue(), "code should be typed into the field");
            // The engine built a query from the descriptor filters.
            OtpQuery q = reader.seen.get();
            assertNotNull(q, "reader should have been called");
            assertEquals("tribucrcorreo@hacienda.go.cr", q.fromContains());
            assertEquals("14528", q.extractCode(
                    "el código de validación es: 14528.").orElseThrow());
            // The code must never be audited.
            assertTrue(audits.stream().anyMatch(a -> a.startsWith("emailOtp:")), "action audited");
            assertFalse(audits.stream().anyMatch(a -> a.contains("14528")), "code must be redacted in audit");
        }
    }

    @Test
    void emailOtp_withoutReader_throwsClearly() {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.navigate(htmlPage("<input id='pin'>"));
            PortalEngine engine = new PortalEngine(   // NO withOtpMailbox
                    page, new HashMap<>(), (a, t) -> {}, p -> "", false);

            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    engine.runSteps("http://example", List.of(emailOtpStep("#pin", oviCfg()))));
            assertTrue(ex.getMessage().contains("OtpMailboxReader"), ex.getMessage());
        }
    }

    @Test
    void emailOtp_withoutCodeRegex_throwsClearly() {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.navigate(htmlPage("<input id='pin'>"));
            PortalEngine engine = new PortalEngine(
                    page, new HashMap<>(), (a, t) -> {}, p -> "", false)
                    .withOtpMailbox(new FakeReader("1"));
            PortalDescriptor.EmailOtp noRegex = new PortalDescriptor.EmailOtp("a", "b", null, 5, 1);

            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    engine.runSteps("http://example", List.of(emailOtpStep("#pin", noRegex))));
            assertTrue(ex.getMessage().contains("codeRegex"), ex.getMessage());
        }
    }

    @Test
    void waitForSelector_hidden_completesWhenElementDisappears() {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            // #login-txt-ident starts present, then a script removes it after 150ms —
            // exactly the "login card replaced after auth" shape.
            page.navigate(htmlPage(
                    "<input id='login-txt-ident'>"
                    + "<script>setTimeout(function(){"
                    + "document.getElementById('login-txt-ident').remove();}, 150);</script>"));

            PortalEngine engine = new PortalEngine(
                    page, new HashMap<>(), (a, t) -> {}, p -> "", false);

            // Should block until the element is gone, then return without throwing.
            engine.runSteps("http://example", List.of(waitHiddenStep("#login-txt-ident")));
            assertEquals(0, page.locator("#login-txt-ident").count(), "element should be gone");
        }
    }
}
