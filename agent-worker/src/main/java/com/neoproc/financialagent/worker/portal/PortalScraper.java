package com.neoproc.financialagent.worker.portal;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
