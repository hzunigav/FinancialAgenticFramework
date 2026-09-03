package com.neoproc.financialagent.worker;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scrape coverage for the CCSS Sicere planilla table against a fixture that
 * reproduces the portal's PrimeFaces paginator: clicking Next leaves the old
 * rows on screen and swaps in the new page — plus the new paginator text —
 * one ajax round-trip later.
 *
 * <p>That delay is the whole point. The scrape used to snapshot the row
 * count and then walk {@code rows.nth(i)} cell by cell, so the swap landed
 * mid-walk. When the incoming page was shorter than the index the walk had
 * reached, {@code nth(i)} pointed at a row that would never exist again and
 * the read hung for Playwright's full 30s default timeout, failing the whole
 * submit with an UNCAUGHT_EXCEPTION. Only clients whose roster spans more
 * than one page could hit it, which is why it presented as "one client is
 * broken, every other client is fine".
 *
 * <p>{@code lastPageIsShorterThanTheScrapeIndex} is that exact shape: 23
 * employees over two pages, the second holding 3 rows.
 */
class CcssSicereScrapeTest {

    /** Rows per page in the fixture; page 2 deliberately runs short. */
    private static final int PAGE_SIZE = 20;
    /** Simulated ajax latency for a paging hop, in ms. */
    private static final int SWAP_DELAY_MS = 400;

    @Test
    void lastPageIsShorterThanTheScrapeIndex() {
        withFixture(23, page -> {
            List<CcssSicereSubmitAdapter.PortalRow> rows =
                    new CcssSicereSubmitAdapter().scrapeAllPages(page, new RunManifest());

            assertEquals(23, rows.size(), "every employee is scraped exactly once");

            List<String> ids = rows.stream()
                    .map(CcssSicereSubmitAdapter.PortalRow::identification)
                    .toList();
            assertEquals(23, ids.stream().distinct().count(),
                    "no employee is scraped twice — a page-1/page-2 splice would duplicate");
            assertEquals("100000000", ids.get(0));
            assertEquals("100000022", ids.get(22), "the short last page is fully scraped");

            // pageIndex drives ensureOnPage during the per-row Aplicar loop;
            // a row tagged with the wrong page fills against the wrong table.
            assertEquals(1, rows.get(19).pageIndex());
            assertEquals(2, rows.get(20).pageIndex());
        });
    }

    @Test
    void singlePagePlanillaScrapesWithoutPaging() {
        withFixture(16, page -> {
            List<CcssSicereSubmitAdapter.PortalRow> rows =
                    new CcssSicereSubmitAdapter().scrapeAllPages(page, new RunManifest());

            assertEquals(16, rows.size());
            assertTrue(rows.stream().allMatch(r -> r.pageIndex() == 1));
        });
    }

    @Test
    void cellsAreReadFromTheDatosSpanNotTheResponsiveColumnTitle() {
        withFixture(16, page -> {
            List<CcssSicereSubmitAdapter.PortalRow> rows =
                    new CcssSicereSubmitAdapter().scrapeAllPages(page, new RunManifest());

            CcssSicereSubmitAdapter.PortalRow first = rows.get(0);
            // PrimeFaces emits a .ui-column-title span in every cell for its
            // stacked mobile layout. Picking it up would prefix every value
            // with its own column header and break both id matching and the
            // money parse.
            assertEquals("0/100000000", first.rawIdentification());
            assertEquals("100000000", first.identification());
            assertEquals("EMPLEADO 00", first.name());
            assertEquals(0, first.currentSalary().compareTo(new java.math.BigDecimal("329820.00")));
            assertFalse(first.inputId().isBlank(), "the row's salary input id is captured");
        });
    }

    // --- fixture ------------------------------------------------------------

    private static void withFixture(int employees, java.util.function.Consumer<Page> body) {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.setContent(fixtureHtml(employees));
            body.accept(page);
        }
    }

    /**
     * A stand-in for the planilla edit page: the same ids and class names the
     * adapter selects on, rows rendered in PrimeFaces' shape (a
     * .ui-column-title span next to a .datos span in every cell), and a
     * paginator whose hops land {@link #SWAP_DELAY_MS} after the click.
     */
    private static String fixtureHtml(int employees) {
        String employeesJs = IntStream.range(0, employees)
                .mapToObj(i -> "{id:'0/" + (100000000 + i) + "',"
                        + "name:'EMPLEADO " + String.format("%02d", i) + "',"
                        + "salary:'329,820.00'}")
                .collect(Collectors.joining(","));

        return FIXTURE
                .replace("__EMPLOYEES__", employeesJs)
                .replace("__PAGE_SIZE__", String.valueOf(PAGE_SIZE))
                .replace("__DELAY__", String.valueOf(SWAP_DELAY_MS));
    }

    private static final String FIXTURE = """
            <!doctype html>
            <html><body>
            <table><tbody id="formAPL:planillaCambiosTable_data"></tbody></table>
            <div id="formAPL:planillaCambiosTable_paginator_bottom" class="ui-paginator">
              <span class="ui-paginator-current"></span>
              <a href="#" class="ui-paginator-first ui-state-default" aria-label="First Page">first</a>
              <a href="#" class="ui-paginator-next ui-state-default" aria-label="Next Page">next</a>
            </div>
            <script>
            const EMPLOYEES = [__EMPLOYEES__];
            const PAGE_SIZE = __PAGE_SIZE__;
            const DELAY = __DELAY__;
            const totalPages = Math.max(1, Math.ceil(EMPLOYEES.length / PAGE_SIZE));
            let current = 1;

            const body = document.getElementById('formAPL:planillaCambiosTable_data');
            const status = document.querySelector('.ui-paginator-current');
            const first = document.querySelector('.ui-paginator-first');
            const next = document.querySelector('.ui-paginator-next');

            function cell(title, value) {
              return '<td role="gridcell"><span class="ui-column-title">' + title
                   + '</span><span class="datos">' + value + '</span></td>';
            }

            function rowHtml(e, i) {
              const rid = 'formAPL:planillaCambiosTable:' + i + ':txtNuevoSalario';
              return '<tr data-ri="' + i + '" role="row">'
                + cell('No. Orden', '1')
                + cell('Identificacion', e.id)
                + cell('Nombre', e.name)
                + cell('Salario', e.salary)
                + '<td role="gridcell"><span class="ui-column-title">Nuevo Salario</span>'
                + '<span id="' + rid + '" class="ui-inputnumber">'
                + '<input id="' + rid + '_input" type="text" class="ui-inputfield">'
                + '<input id="' + rid + '_hinput" type="hidden">'
                + '</span></td>'
                + cell('Observaciones', 'SA-' + e.salary)
                + '</tr>';
            }

            function render() {
              const start = (current - 1) * PAGE_SIZE;
              const slice = EMPLOYEES.slice(start, start + PAGE_SIZE);
              body.innerHTML = slice.map((e, i) => rowHtml(e, start + i)).join('');
              status.textContent = 'Total registros: ' + EMPLOYEES.length
                                 + ' (Página ' + current + ' de ' + totalPages + ')';
              first.className = 'ui-paginator-first ui-state-default'
                             + (current === 1 ? ' ui-state-disabled' : '');
              next.className = 'ui-paginator-next ui-state-default'
                             + (current === totalPages ? ' ui-state-disabled' : '');
            }

            // The portal's paginator is ajax: the click returns immediately and
            // the response replaces the table body AND the paginator text one
            // round-trip later. Until then the page it is leaving is still on
            // screen, which is the window the scrape used to walk into.
            function hop(target) {
              setTimeout(() => { current = target; render(); }, DELAY);
            }
            next.addEventListener('click', ev => {
              ev.preventDefault();
              if (current < totalPages) hop(current + 1);
            });
            first.addEventListener('click', ev => {
              ev.preventDefault();
              if (current > 1) hop(1);
            });
            render();
            </script>
            </body></html>
            """;
}
