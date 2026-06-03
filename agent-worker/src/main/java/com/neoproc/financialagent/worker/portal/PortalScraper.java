package com.neoproc.financialagent.worker.portal;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes the descriptor's scrape section against the current page state
 * and returns a name -> trimmed innerText map. Relies on locator strict
 * semantics per spec §5: a selector matching multiple elements fails fast.
 */
public final class PortalScraper {

    private final Page page;

    public PortalScraper(Page page) {
        this.page = page;
    }

    public Map<String, String> scrape(PortalDescriptor.Scrape scrape) {
        Map<String, String> values = new LinkedHashMap<>();
        if (scrape == null) return values;
        for (PortalDescriptor.Scrape.Field field : scrape.fields()) {
            String text = page.locator(field.selector()).innerText().trim();
            values.put(field.name(), text);
        }
        return values;
    }

    /**
     * Row-mode scrape. For each element matching {@code rowsSelector}, emit
     * a map of column-name → trimmed innerText driven by the per-column
     * selectors in {@code columnSelectors}. Additionally injects a synthetic
     * {@code index} column holding the zero-based row position — adapters
     * typically need it to target an input inside that row later on.
     *
     * <p>Per-column selectors are evaluated with the row locator as the
     * scope, so a selector like {@code .id-cell} resolves within that row,
     * not across the page.
     */
    public List<Map<String, String>> scrapeRows(String rowsSelector,
                                                Map<String, String> columnSelectors) {
        List<Map<String, String>> rows = new ArrayList<>();
        Locator rowLocator = page.locator(rowsSelector);
        int count = rowLocator.count();
        for (int i = 0; i < count; i++) {
            Locator row = rowLocator.nth(i);
            Map<String, String> cells = new LinkedHashMap<>();
            cells.put("index", Integer.toString(i));
            for (Map.Entry<String, String> e : columnSelectors.entrySet()) {
                cells.put(e.getKey(), readCell(row, e.getValue()));
            }
            rows.add(cells);
        }
        return rows;
    }

    /**
     * Paginated row-mode scrape. Walks every page of a row-mode table and
     * returns the concatenated rows. On each page it scrapes via
     * {@link #scrapeRows}, then clicks {@code pagination.nextSelector} and
     * waits for {@code pagination.rangeSelector}'s text to advance before
     * scraping the next page; it stops when the next control is absent or
     * disabled.
     *
     * <p>Rows are de-duplicated by the {@code dedupeBy} column (default
     * {@code id}) so a re-render race that re-scrapes a page can't double-count.
     * The per-page synthetic {@code index} column resets per page — callers
     * that need a global position should key off the dedupe column instead.
     *
     * <p>When {@code pagination} is null or its {@code nextSelector} is blank,
     * this is exactly {@link #scrapeRows} (single page) — so the same call
     * site works for paginated and non-paginated descriptors, and a
     * not-yet-verified {@code nextSelector} that matches nothing degrades to
     * single-page behaviour.
     */
    public List<Map<String, String>> scrapeAllRows(
            String rowsSelector,
            Map<String, String> columnSelectors,
            PortalDescriptor.Scrape.Pagination pagination) {

        if (pagination == null
                || pagination.nextSelector() == null
                || pagination.nextSelector().isBlank()) {
            return scrapeRows(rowsSelector, columnSelectors);
        }

        List<Map<String, String>> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String dedupeKey = pagination.dedupeByOrDefault();
        int maxPages = pagination.maxPagesOrDefault();
        int pages = 0;

        while (true) {
            for (Map<String, String> row : scrapeRows(rowsSelector, columnSelectors)) {
                String key = row.get(dedupeKey);
                if (key == null || key.isBlank() || seen.add(key)) {
                    all.add(row);
                }
            }
            pages++;

            Locator next = page.locator(pagination.nextSelector());
            if (next.count() == 0 || !next.first().isEnabled()) {
                break;  // last page: next control absent or disabled
            }
            if (pages >= maxPages) {
                throw new IllegalStateException(
                        "row pagination exceeded maxPages=" + maxPages
                                + " — next control still enabled: "
                                + pagination.nextSelector()
                                + ". Likely the page is not advancing (wrong"
                                + " rangeSelector?) or the dataset exceeds the cap.");
            }

            String before = pagination.rangeSelector() == null
                    ? null : safeText(pagination.rangeSelector());
            next.first().click();
            waitForAdvance(pagination.rangeSelector(), before);
        }
        return all;
    }

    /**
     * Block until the {@code rangeSelector} text differs from {@code before}
     * (the page advanced), bounded at ~10s. If no range selector is supplied
     * we can't detect the advance precisely, so settle briefly instead. A
     * fall-through is deliberately non-fatal: the next page's scrape +
     * dedupe-by-key absorbs a stale read without double-counting, and a truly
     * stuck pager trips the {@code maxPages} guard.
     */
    private void waitForAdvance(String rangeSelector, String before) {
        if (rangeSelector == null || before == null) {
            page.waitForTimeout(400);
            return;
        }
        for (int i = 0; i < 40; i++) {  // ~10s ceiling at 250ms
            String now = safeText(rangeSelector);
            if (now != null && !now.equals(before)) return;
            page.waitForTimeout(250);
        }
    }

    /** Trimmed innerText of the first match, or null if nothing matches. */
    private String safeText(String selector) {
        Locator loc = page.locator(selector);
        if (loc.count() == 0) return null;
        String text = loc.first().innerText();
        return text == null ? null : text.trim();
    }

    /**
     * Read one cell. Selector convention:
     * <ul>
     *   <li>plain selector → {@code innerText()} (text node)</li>
     *   <li>{@code value:<selector>} → {@code inputValue()} (form input)</li>
     *   <li>{@code attr:<name>:<selector>} → {@code getAttribute(name)} (data-* etc.)</li>
     * </ul>
     */
    private static String readCell(Locator row, String spec) {
        if (spec.startsWith("value:")) {
            return row.locator(spec.substring("value:".length())).inputValue().trim();
        }
        if (spec.startsWith("attr:")) {
            int colon = spec.indexOf(':', "attr:".length());
            if (colon < 0) {
                throw new IllegalArgumentException(
                        "attr: spec must be attr:<name>:<selector> — got: " + spec);
            }
            String attrName = spec.substring("attr:".length(), colon);
            String selector = spec.substring(colon + 1);
            String v = row.locator(selector).getAttribute(attrName);
            return v == null ? "" : v.trim();
        }
        return row.locator(spec).innerText().trim();
    }
}
