package com.financialagent.worker.portal;

import com.financialagent.common.domain.PayrollSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                "totalGrossSalaries", "1,234,567.89",
                "employeeCount", "1-10 of 12");
        PayrollSummary s = AutoplanillaMapper.toSummary(
                scraped, "CRC SANTA ANA TAP HOUSE",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));

        assertEquals("CRC SANTA ANA TAP HOUSE", s.planillaName());
        assertEquals(LocalDate.of(2026, 4, 1), s.fechaInicio());
        assertEquals(LocalDate.of(2026, 4, 30), s.fechaFinal());
        assertEquals(new BigDecimal("1234567.89"), s.totalGrossSalaries());
        assertEquals(12, s.employeeCount());
    }
}
