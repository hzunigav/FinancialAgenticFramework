package com.neoproc.financialagent.worker.portal;

import com.neoproc.financialagent.common.domain.PayrollSummary;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the LIVE autoplanilla descriptor's scrape selectors against both
 * CCSS-report column layouts the portal renders:
 *
 * <ul>
 *   <li>USD planilla &mdash; 7 columns, CRC gross/renta in cols 6/7</li>
 *   <li>CRC planilla &mdash; 4 columns, CRC gross/renta in cols 3/4</li>
 * </ul>
 *
 * The descriptor anchors on {@code :nth-last-child}, so the same selectors
 * read CRC gross/renta from either layout. Regression guard for the
 * 2026-06-02 MAP SOLUCIONES incident, where hash/position selectors built
 * for the USD report ({@code css-1wj4860}, {@code td:nth-child(6)}) timed
 * out on a CRC-only planilla.
 *
 * <p>Loading via {@link PortalDescriptorLoader} (rather than a synthetic
 * Scrape) means this also asserts the production YAML parses. The fixtures
 * are minimal DOM models of the two reports — including the colspan'd
 * "Total (CRC)" footer label — to prove nth-last-child counts correctly
 * from the right regardless of the leading label span.
 */
class AutoplanillaScrapeFixtureTest {

    private static final String USD_REPORT = """
            <html><body>
            <table>
              <tbody>
                <tr class="MuiTableRow-root">
                  <td>117040400</td><td>ANDRES MARIN</td><td>1,934.81</td>
                  <td>0.00</td><td>451.00</td><td>872,599.31</td><td>0.00</td>
                </tr>
                <tr class="MuiTableRow-root">
                  <td>114230204</td><td>ANDRES MONTERO</td><td>1,900.00</td>
                  <td>0.00</td><td>451.00</td><td>856,900.00</td><td>0.00</td>
                </tr>
              </tbody>
              <tfoot>
                <tr>
                  <td class="MuiTableCell-footer" colspan="2">Total (CRC)</td>
                  <td class="MuiTableCell-footer">37,222.96</td>
                  <td class="MuiTableCell-footer">1,527.54</td>
                  <td class="MuiTableCell-footer">Avg: 451.00</td>
                  <td class="MuiTableCell-footer">16,787,554.96</td>
                  <td class="MuiTableCell-footer">688,920.54</td>
                </tr>
              </tfoot>
            </table>
            <div class="MuiBox-root css-10gei56"><div class="MuiBox-root css-1llu0od"><span></span><div class="MuiBox-root css-qpxlqp"><div class="MuiTablePagination-root MuiBox-root css-8k4lth"><div class="MuiBox-root css-exd1zr"><label>Rows per page</label></div><span class="MuiTypography-root MuiTypography-body2 MuiTypography-alignCenter css-13lts0x">1-10 of 14</span><div class="MuiBox-root css-192dx0w"></div></div></div></div></div>
            </body></html>
            """;

    private static final String CRC_REPORT = """
            <html><body>
            <table>
              <tbody>
                <tr class="MuiTableRow-root">
                  <td>116870125</td><td>Aaron Ruiz</td><td>495,000.00</td><td>0.00</td>
                </tr>
                <tr class="MuiTableRow-root">
                  <td>MAP1</td><td>Adrian Antonio Chinchilla</td><td>396,000.00</td><td>0.00</td>
                </tr>
              </tbody>
              <tfoot>
                <tr>
                  <td class="MuiTableCell-footer" colspan="2">Total (CRC)</td>
                  <td class="MuiTableCell-footer">39,456,492.77</td>
                  <td class="MuiTableCell-footer">29,200.00</td>
                </tr>
              </tfoot>
            </table>
            <div class="MuiBox-root css-10gei56"><div class="MuiBox-root css-1llu0od"><span></span><div class="MuiBox-root css-qpxlqp"><div class="MuiTablePagination-root MuiBox-root css-8k4lth"><div class="MuiBox-root css-exd1zr"><label>Rows per page</label></div><span class="MuiTypography-root MuiTypography-body2 MuiTypography-alignCenter css-13lts0x">1-10 of 92</span><div class="MuiBox-root css-192dx0w"></div></div></div></div></div>
            </body></html>
            """;

    private static PortalDescriptor.Scrape autoplanillaScrape() throws IOException {
        return PortalDescriptorLoader.load("autoplanilla").scrape();
    }

    @Test
    void usdLayout_capturesCrcGrossRentaCountAndRows() throws IOException {
        DescriptorFixture.ScrapeResult result =
                DescriptorFixture.runScrape(USD_REPORT, autoplanillaScrape());

        // CRC gross/renta are the last two footer cells (cols 6/7 here).
        assertEquals("16,787,554.96", result.fields().get("totalGrossSalaries"));
        assertEquals("688,920.54",    result.fields().get("totalRenta"));
        assertEquals("1-10 of 14",    result.fields().get("employeeCount"));
        // Per-row gross is the CRC column (col 6), not the USD column (col 3).
        assertEquals("117040400",    result.rows().get(0).get("id"));
        assertEquals("ANDRES MARIN", result.rows().get(0).get("name"));
        assertEquals("872,599.31",   result.rows().get(0).get("grossSalary"));

        PayrollSummary s = AutoplanillaMapper.toSummary(
                result.fields(), result.rows(), "NeoProc Quincenal USD",
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));
        assertEquals(new BigDecimal("16787554.96"), s.totalGrossSalaries());
        assertEquals(new BigDecimal("688920.54"),   s.totalRenta());
        assertEquals(14, s.employeeCount());
        assertEquals(new BigDecimal("872599.31"), s.employees().get(0).grossSalary());
    }

    @Test
    void crcLayout_capturesCrcGrossRentaCountAndRows() throws IOException {
        DescriptorFixture.ScrapeResult result =
                DescriptorFixture.runScrape(CRC_REPORT, autoplanillaScrape());

        // CRC gross/renta are the last two footer cells (cols 3/4 here).
        assertEquals("39,456,492.77", result.fields().get("totalGrossSalaries"));
        assertEquals("29,200.00",     result.fields().get("totalRenta"));
        assertEquals("1-10 of 92",    result.fields().get("employeeCount"));
        assertEquals("116870125",  result.rows().get(0).get("id"));
        assertEquals("495,000.00", result.rows().get(0).get("grossSalary"));

        PayrollSummary s = AutoplanillaMapper.toSummary(
                result.fields(), result.rows(), "MAP SOLUCIONES QUINCENAL CRC",
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));
        assertEquals(new BigDecimal("39456492.77"), s.totalGrossSalaries());
        assertEquals(new BigDecimal("29200.00"),    s.totalRenta());
        assertEquals(92, s.employeeCount());
        assertEquals(new BigDecimal("495000.00"), s.employees().get(0).grossSalary());
    }
}
