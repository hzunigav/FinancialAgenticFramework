package com.financialagent.worker.portal;

import com.microsoft.playwright.Page;

import java.util.LinkedHashMap;
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
}
