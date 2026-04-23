package com.neoproc.financialagent.worker.portal;

import com.neoproc.financialagent.common.domain.PayrollEmployeeRow;
import com.neoproc.financialagent.common.domain.PayrollSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoplanillaMapperTest {

    @Test
    void parseCurrency_plainInteger() {
        assertEquals(new BigDecimal("12345"), AutoplanillaMapper.parseCurrency("12345"));
    }

    @Test
    void parseCurrency_thousandsAndDecimal() {
        assertEquals(new BigDecimal("1234567.89"),
                AutoplanillaMapper.parseCurrency("1,234,567.89"));
    }

    @Test
    void parseCurrency_colonPrefix() {
        assertEquals(new BigDecimal("500000.00"),
                AutoplanillaMapper.parseCurrency("₡1,000,000.00".replace("1,000,000.00", "500,000.00")));
    }

    @Test
    void parseCurrency_crcPrefix() {
        assertEquals(new BigDecimal("250000.50"),
                AutoplanillaMapper.parseCurrency("CRC 250,000.50"));
    }

    @Test
    void parseCurrency_zero() {
        assertEquals(new BigDecimal("0.00"), AutoplanillaMapper.parseCurrency("0.00"));
    }

    @Test
    void parseCurrency_bogusInput_throws() {
        assertThrows(IllegalStateException.class,
                () -> AutoplanillaMapper.parseCurrency("not a number"));
    }

    @Test
    void parseEmployeeCount_paginationText() {
        assertEquals(47, AutoplanillaMapper.parseEmployeeCount("1-10 of 47"));
        assertEquals(0, AutoplanillaMapper.parseEmployeeCount("0-0 of 0"));
        assertEquals(250, AutoplanillaMapper.parseEmployeeCount("Rows per page 10 1-10 of 250"));
    }

    @Test
    void parseEmployeeCount_bareNumber() {
        assertEquals(5, AutoplanillaMapper.parseEmployeeCount("5"));
        assertEquals(12, AutoplanillaMapper.parseEmployeeCount(" 12 "));
    }

    @Test
    void parseEmployeeCount_caseInsensitive() {
        assertEquals(47, AutoplanillaMapper.parseEmployeeCount("1-10 OF 47"));
    }

    @Test
    void toSummary_packagesAllFields() {
        Map<String, String> scraped = Map.of(
                "totalGrossSalaries", "25,932,489.92",
                "totalRenta", "992,025.46",
                "employeeCount", "1-10 of 13");
        PayrollSummary s = AutoplanillaMapper.toSummary(
                scraped, List.of(), "NeoProc Quincenal USD",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));

        assertEquals("NeoProc Quincenal USD", s.planillaName());
        assertEquals(LocalDate.of(2026, 4, 1), s.fechaInicio());
        assertEquals(LocalDate.of(2026, 4, 30), s.fechaFinal());
        assertEquals(new BigDecimal("25932489.92"), s.totalGrossSalaries());
        assertEquals(new BigDecimal("992025.46"), s.totalRenta());
        assertEquals(13, s.employeeCount());
        assertTrue(s.employees().isEmpty());
    }

    @Test
    void toSummary_missingRenta_throws() {
        Map<String, String> scraped = Map.of(
                "totalGrossSalaries", "25,932,489.92",
                "employeeCount", "1-10 of 13");
        assertThrows(IllegalStateException.class,
                () -> AutoplanillaMapper.toSummary(
                        scraped, List.of(), "NeoProc Quincenal USD",
                        LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)));
    }

    @Test
    void toEmployeeRows_parsesIdNameAndCurrency() {
        List<Map<String, String>> scrapedRows = List.of(
                Map.of("index", "0",
                        "id", "0-110080108",
                        "name", "OLGER ULATE ROJAS",
                        "grossSalary", "1,350,635.76"),
                Map.of("index", "1",
                        "id", "0-114230204",
                        "name", "CARLOS ANDRES MONTERO ARCE",
                        "grossSalary", "1,328,100.00"));

        List<PayrollEmployeeRow> rows = AutoplanillaMapper.toEmployeeRows(scrapedRows);

        assertEquals(2, rows.size());
        assertEquals("0-110080108", rows.get(0).id());
        assertEquals("OLGER ULATE ROJAS", rows.get(0).name());
        assertEquals(new BigDecimal("1350635.76"), rows.get(0).grossSalary());
        assertEquals(new BigDecimal("1328100.00"), rows.get(1).grossSalary());
    }

    @Test
    void toEmployeeRows_emptyOrNullReturnsEmpty() {
        assertTrue(AutoplanillaMapper.toEmployeeRows(null).isEmpty());
        assertTrue(AutoplanillaMapper.toEmployeeRows(List.of()).isEmpty());
    }

    @Test
    void toEmployeeRows_missingColumnThrows() {
        List<Map<String, String>> bad = List.of(
                Map.of("id", "0-110080108", "name", "OLGER ULATE ROJAS"));
        assertThrows(IllegalStateException.class,
                () -> AutoplanillaMapper.toEmployeeRows(bad));
    }

    @Test
    void toSummary_withRows_carriesEmployeeList() {
        Map<String, String> scraped = Map.of(
                "totalGrossSalaries", "2,678,735.76",
                "totalRenta", "0.00",
                "employeeCount", "1-2 of 2");
        List<Map<String, String>> scrapedRows = List.of(
                Map.of("index", "0", "id", "0-110080108",
                        "name", "OLGER ULATE ROJAS", "grossSalary", "1,350,635.76"),
                Map.of("index", "1", "id", "0-114230204",
                        "name", "CARLOS ANDRES MONTERO ARCE", "grossSalary", "1,328,100.00"));

        PayrollSummary s = AutoplanillaMapper.toSummary(
                scraped, scrapedRows, "NeoProc Quincenal USD",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

        assertEquals(2, s.employees().size());
        assertEquals("OLGER ULATE ROJAS", s.employees().get(0).name());
    }
}
