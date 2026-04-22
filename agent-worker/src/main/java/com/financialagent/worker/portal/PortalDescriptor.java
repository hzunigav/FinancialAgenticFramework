package com.financialagent.worker.portal;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Declarative portal adapter. The engine knows how to drive a browser; the
 * descriptor knows the shape of one specific portal. New portal = new YAML,
 * not new Java.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortalDescriptor(
        String id,
        String description,
        String baseUrl,
        List<Step> steps,
        Scrape scrape) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(
            Action action,
            String selector,
            String target,
            String value,
            @JsonAlias("redact") Boolean redactValue) {

        public boolean redacted() {
            return Boolean.TRUE.equals(redactValue);
        }
    }

    public enum Action { navigate, fill, click, waitForUrl }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scrape(List<Field> fields) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Field(String name, String selector) {}
    }
}
