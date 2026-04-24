package com.neoproc.financialagent.worker.portal;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.neoproc.financialagent.worker.portal.PortalDescriptor.Scrape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Test helper for running a portal descriptor's scrape step against a
 * captured HTML fixture — no live portal required.
 *
 * <p>Portal operators produce fixtures by running the real agent with
 * {@code -Dfixture.capture=true}; the resulting
 * {@code artifacts/<runId>/fixtures/<portalId>-post-steps.html} is the
 * fully-rendered DOM at the moment the scrape would fire. Copying that
 * file under {@code agent-worker/src/test/resources/fixtures/<portalId>/}
 * makes it a permanent regression fixture — descriptor tweaks (new
 * selector, different field name) can be iterated entirely in tests.
 *
 * <p>Typical test shape:
 * <pre>{@code
 * DescriptorFixture.ScrapeResult result = DescriptorFixture.runScrape(
 *         Path.of("src/test/resources/fixtures/autoplanilla/post-steps.html"),
 *         loadAutoplanillaDescriptor().scrape());
 * assertEquals("17,239,260.72", result.fields().get("totalGrossSalaries"));
 * }</pre>
 *
 * <p>This only exercises the {@code scrape:} section. Running full
 * {@code steps:} sequences against a static fixture is ill-defined —
 * most steps mutate state, and the fixture is a single moment in time.
 * For step-level regression testing, capture a fresh fixture after the
 * step runs and assert on that.
 */
public final class DescriptorFixture {

    private DescriptorFixture() {}

    /**
     * Run the descriptor's scrape (both aggregate fields and row-mode, if
     * present) against a fixture HTML file.
     *
     * @param fixturePath path to the HTML fixture file
     * @param scrape      the descriptor's scrape section
     * @return a {@link ScrapeResult} with the aggregate fields and, if the
     *         descriptor declared rows, the row list
     */
    public static ScrapeResult runScrape(Path fixturePath, Scrape scrape) {
        try {
            return runScrape(Files.readString(fixturePath), scrape);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "failed to read fixture: " + fixturePath, e);
        }
    }

    /** Variant that takes already-loaded HTML instead of a file path. */
    public static ScrapeResult runScrape(String html, Scrape scrape) {
        if (scrape == null) {
            return new ScrapeResult(Map.of(), List.of());
        }
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(
                     new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.setContent(html);
            PortalScraper scraper = new PortalScraper(page);
            Map<String, String> fields = scraper.scrape(scrape);
            List<Map<String, String>> rows = List.of();
            if (scrape.rows() != null && scrape.rows().selector() != null) {
                rows = scraper.scrapeRows(scrape.rows().selector(), scrape.rows().columns());
            }
            return new ScrapeResult(fields, rows);
        }
    }

    /** Aggregate fields and (optional) per-row captures from a fixture run. */
    public record ScrapeResult(Map<String, String> fields, List<Map<String, String>> rows) {}
}
