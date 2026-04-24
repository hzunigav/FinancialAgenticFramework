package com.neoproc.financialagent.worker.portal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link DescriptorFixture} end-to-end so the harness itself
 * regresses alongside the engine. Uses synthetic HTML rather than a
 * captured portal fixture — real per-portal fixtures are created by
 * Phase 2 authors when they onboard their descriptor.
 */
class DescriptorFixtureTest {

    @Test
    void runScrape_aggregateFields_readsSelectorsFromSetContentHtml() {
        String html = "<html><body>"
                + "<div id='total-gross'>1,234,567.89</div>"
                + "<div id='total-renta'>456,789.01</div>"
                + "<div id='count'>1-13 of 13</div>"
                + "</body></html>";

        PortalDescriptor.Scrape scrape = new PortalDescriptor.Scrape(
                List.of(
                        new PortalDescriptor.Scrape.Field("totalGrossSalaries", "#total-gross"),
                        new PortalDescriptor.Scrape.Field("totalRenta", "#total-renta"),
                        new PortalDescriptor.Scrape.Field("employeeCount", "#count")),
                null);

        DescriptorFixture.ScrapeResult result = DescriptorFixture.runScrape(html, scrape);

        assertEquals("1,234,567.89", result.fields().get("totalGrossSalaries"));
        assertEquals("456,789.01",   result.fields().get("totalRenta"));
        assertEquals("1-13 of 13",    result.fields().get("employeeCount"));
        assertTrue(result.rows().isEmpty(), "no row selector declared — rows should be empty");
    }

    @Test
    void runScrape_rowMode_returnsOneMapPerRow() {
        String html = "<html><body>"
                + "<table><tbody>"
                + "<tr class='emp'><td class='id'>A1</td><td class='name'>ALICE</td><td class='salary'>100</td></tr>"
                + "<tr class='emp'><td class='id'>B2</td><td class='name'>BOB</td><td class='salary'>200</td></tr>"
                + "</tbody></table>"
                + "</body></html>";

        PortalDescriptor.Scrape scrape = new PortalDescriptor.Scrape(
                List.of(),
                new PortalDescriptor.Scrape.RowSpec(
                        "tbody > tr.emp",
                        Map.of(
                                "id", ".id",
                                "name", ".name",
                                "grossSalary", ".salary")));

        DescriptorFixture.ScrapeResult result = DescriptorFixture.runScrape(html, scrape);

        assertEquals(2, result.rows().size());
        assertEquals("A1",    result.rows().get(0).get("id"));
        assertEquals("ALICE", result.rows().get(0).get("name"));
        assertEquals("100",   result.rows().get(0).get("grossSalary"));
        assertEquals("B2",    result.rows().get(1).get("id"));
    }
}
