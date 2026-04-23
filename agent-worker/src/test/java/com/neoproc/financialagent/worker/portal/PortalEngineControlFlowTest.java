package com.neoproc.financialagent.worker.portal;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the control-flow actions added so Phase 2 portal authors
 * can express conditionals, assertions, and pagination loops
 * declaratively: {@code when}, {@code expect}, {@code whileLoop}.
 *
 * <p>These tests use a headless Chromium page against data: URLs so we
 * exercise the real Playwright locator paths without needing a running
 * portal or mock server. Each test sets up a minimal static HTML page,
 * runs descriptor steps against it, and asserts the observable side
 * effects (which substeps ran, what was audited, whether the expected
 * exception fired).
 */
class PortalEngineControlFlowTest {

    /** Builds a data: URL page whose body contains the given HTML. */
    private static String htmlPage(String bodyHtml) {
        return "data:text/html,<!doctype html><html><body>" + bodyHtml + "</body></html>";
    }

    private static PortalDescriptor.Step step(PortalDescriptor.Action action,
                                              String selector,
                                              List<PortalDescriptor.Step> then,
                                              List<PortalDescriptor.Step> orElse,
                                              Boolean exists,
                                              String containsText,
                                              String matchesRegex,
                                              Integer hasCount,
                                              Integer maxIterations) {
        return new PortalDescriptor.Step(
                action, selector, null, null, null, null, null, null,
                null, null, then,
                exists, orElse, containsText, matchesRegex, hasCount, maxIterations);
    }

    private static PortalDescriptor.Step auditOnlyStep(String tag) {
        // Use pause to observe which branch ran — it writes to the audit
        // listener and does not touch the page.
        return new PortalDescriptor.Step(
                PortalDescriptor.Action.pause,
                null, null, null, null, tag, "noop", null,
                null, null, null,
                null, null, null, null, null, null);
    }

    @Test
    void when_selectorExists_runsThenBranch() {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.navigate(htmlPage("<button id='accept'>Accept</button>"));

            List<String> audits = new ArrayList<>();
            PortalEngine engine = new PortalEngine(
                    page, new HashMap<>(),
                    (a, t) -> audits.add(a + ":" + t),
                    p -> { audits.add("PAUSE:" + p); return ""; },
                    false);

            PortalDescriptor.Step whenStep = step(
                    PortalDescriptor.Action.when,
                    "#accept",
                    List.of(auditOnlyStep("THEN-RAN")),
                    List.of(auditOnlyStep("ELSE-RAN")),
                    true, null, null, null, null);

            engine.runSteps("http://example", List.of(whenStep));

            assertTrue(audits.stream().anyMatch(a -> a.contains("THEN-RAN")),
                    "then branch should have run: " + audits);
            assertTrue(audits.stream().noneMatch(a -> a.contains("ELSE-RAN")),
                    "else branch should NOT have run: " + audits);
        }
    }

    @Test
    void when_selectorMissing_runsElseBranch() {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.navigate(htmlPage("<p>No button here</p>"));

            List<String> audits = new ArrayList<>();
            PortalEngine engine = new PortalEngine(
                    page, new HashMap<>(),
                    (a, t) -> audits.add(a + ":" + t),
                    p -> { audits.add("PAUSE:" + p); return ""; },
                    false);

            PortalDescriptor.Step whenStep = step(
                    PortalDescriptor.Action.when,
                    "#accept",
                    List.of(auditOnlyStep("THEN-RAN")),
                    List.of(auditOnlyStep("ELSE-RAN")),
                    true, null, null, null, null);

            engine.runSteps("http://example", List.of(whenStep));

            assertTrue(audits.stream().anyMatch(a -> a.contains("ELSE-RAN")),
                    "else branch should have run: " + audits);
            assertTrue(audits.stream().noneMatch(a -> a.contains("THEN-RAN")),
                    "then branch should NOT have run: " + audits);
        }
    }

    @Test
    void when_selectorMissing_noElse_isNoOp() {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.navigate(htmlPage("<p>Empty</p>"));

            PortalEngine engine = new PortalEngine(
                    page, new HashMap<>(),
                    (a, t) -> {},
                    p -> { throw new AssertionError("pause should not fire"); },
                    false);

            PortalDescriptor.Step whenStep = step(
                    PortalDescriptor.Action.when,
                    "#missing",
                    List.of(auditOnlyStep("THEN-RAN")),
                    null,
                    true, null, null, null, null);

            engine.runSteps("http://example", List.of(whenStep));
            // No assertion beyond the pause-sentinel: if it didn't throw, we're good.
        }
    }

    @Test
    void expect_containsText_matchesActualPassesSilently() {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.navigate(htmlPage("<div id='status'>Aceptado - registro exitoso</div>"));

            List<String> audits = new ArrayList<>();
            PortalEngine engine = new PortalEngine(
                    page, new HashMap<>(),
                    (a, t) -> audits.add(a + ":" + t),
                    p -> "",
                    false);

            PortalDescriptor.Step expectStep = step(
                    PortalDescriptor.Action.expect,
                    "#status",
                    null, null, null,
                    "Aceptado", null, null, null);

            engine.runSteps("http://example", List.of(expectStep));

            assertTrue(audits.stream().anyMatch(a -> a.startsWith("expect:")),
                    "expect should have been audited on success: " + audits);
        }
    }

    @Test
    void expect_containsText_mismatchThrowsExpectationFailed() {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.navigate(htmlPage("<div id='status'>Rechazado - error de validación</div>"));

            PortalEngine engine = new PortalEngine(
                    page, new HashMap<>(),
                    (a, t) -> {},
                    p -> "",
                    false);

            PortalDescriptor.Step expectStep = step(
                    PortalDescriptor.Action.expect,
                    "#status",
                    null, null, null,
                    "Aceptado", null, null, null);

            PortalEngine.ExpectationFailed ex = assertThrows(
                    PortalEngine.ExpectationFailed.class,
                    () -> engine.runSteps("http://example", List.of(expectStep)));
            assertTrue(ex.getMessage().contains("Aceptado"),
                    "error should name the expected substring");
        }
    }

    @Test
    void expect_hasCount_matchesPasses() {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.navigate(htmlPage("<li class='item'>a</li><li class='item'>b</li><li class='item'>c</li>"));

            PortalEngine engine = new PortalEngine(
                    page, new HashMap<>(),
                    (a, t) -> {},
                    p -> "",
                    false);

            PortalDescriptor.Step expectStep = step(
                    PortalDescriptor.Action.expect,
                    ".item",
                    null, null, null,
                    null, null, 3, null);

            engine.runSteps("http://example", List.of(expectStep));
        }
    }

    @Test
    void expect_noAssertionSet_throws() {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.navigate(htmlPage("<p/>"));

            PortalEngine engine = new PortalEngine(
                    page, new HashMap<>(),
                    (a, t) -> {},
                    p -> "",
                    false);

            PortalDescriptor.Step expectStep = step(
                    PortalDescriptor.Action.expect,
                    "#nothing",
                    null, null, null,
                    null, null, null, null);

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> engine.runSteps("http://example", List.of(expectStep)));
            assertTrue(ex.getMessage().contains("containsText"),
                    "error should hint at which fields can be set: " + ex.getMessage());
        }
    }

    @Test
    void whileLoop_runsUntilSelectorDisappears() {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            // setContent (rather than data: URL) allows inline onclick
            // handlers to run without CSP interference. Each button's
            // onclick removes itself, so after 3 clicks the selector
            // no longer matches and the while loop exits.
            page.setContent(
                    "<html><body>"
                    + "<button class='next' onclick='this.remove()'>n1</button>"
                    + "<button class='next' onclick='this.remove()'>n2</button>"
                    + "<button class='next' onclick='this.remove()'>n3</button>"
                    + "</body></html>");

            List<String> audits = new ArrayList<>();
            PortalEngine engine = new PortalEngine(
                    page, new HashMap<>(),
                    (a, t) -> audits.add(a + ":" + t),
                    p -> "",
                    false);

            // While-condition matches any .next; body targets the first one
            // unambiguously (Playwright's locator().click() is strict-mode
            // by default and refuses to click a selector that matches
            // multiple elements).
            PortalDescriptor.Step body = new PortalDescriptor.Step(
                    PortalDescriptor.Action.click,
                    ".next:first-of-type", null, null, null, null, null, null,
                    null, null, null,
                    null, null, null, null, null, null);
            PortalDescriptor.Step whileStep = step(
                    PortalDescriptor.Action.whileLoop,
                    ".next",
                    List.of(body), null, null, null, null, null, null);

            engine.runSteps("http://example", List.of(whileStep));

            long clicks = audits.stream().filter(a -> a.startsWith("click:")).count();
            assertEquals(3, clicks, "should click exactly 3 times: " + audits);
            assertTrue(audits.stream().anyMatch(a -> a.equals("while:end iterations=3")),
                    "while should report iterations=3 on end: " + audits);
        }
    }

    @Test
    void whileLoop_exceedingMaxIterations_throws() {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            // .next always exists and the body does nothing to remove it,
            // so the loop would be infinite without the safety cap.
            page.navigate(htmlPage("<button class='next'>persistent</button>"));

            PortalEngine engine = new PortalEngine(
                    page, new HashMap<>(),
                    (a, t) -> {},
                    p -> "",
                    false);

            PortalDescriptor.Step noop = new PortalDescriptor.Step(
                    PortalDescriptor.Action.pause,
                    null, null, null, null, "tick", "ignored", null,
                    null, null, null,
                    null, null, null, null, null, null);
            PortalDescriptor.Step whileStep = step(
                    PortalDescriptor.Action.whileLoop,
                    ".next",
                    List.of(noop), null, null, null, null, null, 3);

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> engine.runSteps("http://example", List.of(whileStep)));
            assertTrue(ex.getMessage().contains("maxIterations=3"),
                    "error should name the cap: " + ex.getMessage());
        }
    }

}
