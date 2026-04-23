package com.neoproc.financialagent.worker.portal;

import com.neoproc.financialagent.common.domain.PayrollEmployeeRow;
import com.neoproc.financialagent.common.domain.PayrollSummary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the scraper's flat string map into a {@link PayrollSummary}, parsing
 * the AutoPlanilla CCSS-report shape: Costa Rica currency (grouping comma,
 * period decimal, with or without a {@code ₡} / {@code CRC} prefix) and the
 * Material-React-Table footer of form {@code "1-10 of 47"} from which the
 * employee count is the number after {@code "of"}.
 */
public final class AutoplanillaMapper {

    private static final Pattern ROW_COUNT = Pattern.compile("of\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private AutoplanillaMapper() {}

    public static PayrollSummary toSummary(Map<String, String> scraped,
                                           List<Map<String, String>> scrapedRows,
                                           String planillaName,
                                           LocalDate fechaInicio,
                                           LocalDate fechaFinal) {
        BigDecimal total = parseCurrency(require(scraped, "totalGrossSalaries"));
        BigDecimal renta = parseCurrency(require(scraped, "totalRenta"));
        int employees = parseEmployeeCount(require(scraped, "employeeCount"));
        List<PayrollEmployeeRow> rows = toEmployeeRows(scrapedRows);
        return new PayrollSummary(planillaName, fechaInicio, fechaFinal,
                total, renta, employees, rows);
    }

    static List<PayrollEmployeeRow> toEmployeeRows(List<Map<String, String>> scrapedRows) {
        if (scrapedRows == null || scrapedRows.isEmpty()) return List.of();
        List<PayrollEmployeeRow> out = new ArrayList<>(scrapedRows.size());
        for (Map<String, String> row : scrapedRows) {
            String id = require(row, "id");
            String name = require(row, "name");
            BigDecimal salary = parseCurrency(require(row, "grossSalary"));
            out.add(new PayrollEmployeeRow(id, name, salary));
        }
        return out;
    }

    static BigDecimal parseCurrency(String raw) {
        String cleaned = raw
                .replace("₡", "")      // ₡
                .replace("CRC", "")
                .replace(" ", " ")     // non-breaking space
                .trim()
                .replace(",", "");
        if (cleaned.isEmpty()) {
            throw new IllegalStateException("Currency value is empty after cleaning");
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Failed to parse currency value: \"" + raw + "\"", e);
        }
    }

    static int parseEmployeeCount(String raw) {
        Matcher m = ROW_COUNT.matcher(raw);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        // Fallback: the whole string is a bare integer (e.g. cell count scrape)
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Failed to parse employee count from: \"" + raw + "\"", e);
        }
    }

    private static String require(Map<String, String> map, String key) {
        String v = map.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Scraped value missing or blank: " + key);
        }
        return v;
    }
}
