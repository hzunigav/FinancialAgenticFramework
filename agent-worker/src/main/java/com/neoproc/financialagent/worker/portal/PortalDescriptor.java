package com.neoproc.financialagent.worker.portal;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

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
        Boolean shadowMode,
        SessionConfig session,
        List<Step> authSteps,
        List<Step> steps,
        Scrape scrape,
        SecurityContext securityContext) {

    public boolean isShadowMode() {
        return Boolean.TRUE.equals(shadowMode);
    }

    public List<Step> authSteps() {
        return authSteps == null ? List.of() : authSteps;
    }

    public List<Step> steps() {
        return steps == null ? List.of() : steps;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(
            Action action,
            String selector,
            String target,
            String value,
            @JsonAlias("redact") Boolean redactValue,
            String prompt,
            String bindTo,
            Boolean submit,
            String over,
            String item,
            List<Step> steps) {

        public boolean redacted() {
            return Boolean.TRUE.equals(redactValue);
        }

        public boolean isSubmit() {
            return Boolean.TRUE.equals(submit);
        }

        public List<Step> steps() {
            return steps == null ? List.of() : steps;
        }
    }

    public enum Action { navigate, fill, click, waitForUrl, waitForSelector, select, pause, forEach }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionConfig(Integer ttlMinutes) {

        public boolean enabled() {
            return ttlMinutes != null && ttlMinutes > 0;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scrape(List<Field> fields, RowSpec rows) {

        public List<Field> fields() {
            return fields == null ? List.of() : fields;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Field(String name, String selector) {}

        /**
         * Row-mode scrape: emit one map per element matching {@code selector},
         * with keys/values from {@code columns}. Selectors in {@code columns}
         * are evaluated relative to each row, not the page. Optional —
         * absent for capture-only-totals portals.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record RowSpec(String selector, Map<String, String> columns) {}
    }

    /**
     * Declarative post-processing applied after the run completes but before
     * artifacts are sealed. Applied to the HAR file to strip secret request
     * fields; the live {@code network.har} produced by Playwright is never
     * kept — only the scrubbed copy is written to disk.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SecurityContext(List<HarScrubRule> scrubHarFields) {

        public List<HarScrubRule> scrubHarFields() {
            return scrubHarFields == null ? List.of() : scrubHarFields;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record HarScrubRule(String urlPattern, List<String> bodyFields) {}
    }
}
