package com.financialagent.worker.portal;

import com.financialagent.common.domain.PayrollSummary;

import java.math.BigDecimal;
import java.time.LocalDate;
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
                                           String planillaName,
                                           LocalDate fechaInicio,
                                           LocalDate fechaFinal) {
        BigDecimal total = parseCurrency(require(scraped, "totalGrossSalaries"));
        int employees = parseEmployeeCount(require(scraped, "employeeCount"));
        return new PayrollSummary(planillaName, fechaInicio, fechaFinal, total, employees);
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
