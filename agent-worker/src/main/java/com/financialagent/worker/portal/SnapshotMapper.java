package com.financialagent.worker.portal;

import com.financialagent.common.domain.ReportSnapshot;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Maps the scraper's flat string map into a typed {@link ReportSnapshot}.
 * Kept separate from the scraper so parsing rules are explicit and easy to
 * evolve when the domain record grows.
 */
public final class SnapshotMapper {

    private SnapshotMapper() {}

    public static ReportSnapshot toSnapshot(Map<String, String> scraped) {
        String username = require(scraped, "username");
        String rawDate = require(scraped, "reportDate");
        LocalDate reportDate;
        try {
            reportDate = LocalDate.parse(rawDate);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                    "Scraped reportDate is not ISO-8601 local date: " + rawDate, e);
        }
        return new ReportSnapshot(username, reportDate);
    }

    private static String require(Map<String, String> map, String key) {
        String v = map.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Scraped value missing or blank: " + key);
        }
        return v;
    }
}
