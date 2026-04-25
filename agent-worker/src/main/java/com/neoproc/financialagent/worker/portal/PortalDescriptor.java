package com.neoproc.financialagent.worker.portal;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

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
        String credentialScope,
        RateLimit rateLimit,
        SessionConfig session,
        List<Step> authSteps,
        List<Step> steps,
        Scrape scrape,
        SecurityContext securityContext) {

    public static final String SCOPE_PER_FIRM = "per-firm";
    public static final String SCOPE_SHARED = "shared";

    public boolean isShadowMode() {
        return Boolean.TRUE.equals(shadowMode);
    }

    /**
     * How portal credentials are scoped at the source of truth. Drives the
     * Vault path the credentials provider reads from (per-firm subtree vs
     * shared subtree). Defaults to per-firm — the safer assumption for a
     * new portal, since leaking shared credentials to the wrong tenant is
     * the worse failure mode.
     */
    public String credentialScope() {
        return credentialScope == null ? SCOPE_PER_FIRM : credentialScope;
    }

    public boolean hasSharedCredentials() {
        return SCOPE_SHARED.equals(credentialScope());
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
            // forEach: list-binding name and per-item scope name
            String over,
            String item,
            // forEach body / when "then" branch / while body
            List<Step> steps,
            // when: predicate + optional "else" branch
            Boolean exists,
            List<Step> elseSteps,
            // expect: exactly one of containsText / matchesRegex / hasCount must be set
            String containsText,
            String matchesRegex,
            Integer hasCount,
            // while: safety cap on the loop (defaults to 100)
            Integer maxIterations) {

        public boolean redacted() {
            return Boolean.TRUE.equals(redactValue);
        }

        public boolean isSubmit() {
            return Boolean.TRUE.equals(submit);
        }

        public List<Step> steps() {
            return steps == null ? List.of() : steps;
        }

        public List<Step> elseSteps() {
            return elseSteps == null ? List.of() : elseSteps;
        }

        /** Default max iterations for a {@code while} loop — prevents runaway
         *  loops when the body fails to change the condition. 100 is enough
         *  for any realistic pagination; if a real portal needs more,
         *  override at the descriptor level. */
        public int maxIterationsOrDefault() {
            return maxIterations == null ? 100 : maxIterations;
        }
    }

    public enum Action {
        navigate, fill, click, waitForUrl, waitForSelector, select, pause, forEach,
        when, expect,
        @JsonProperty("while") whileLoop
    }

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
     * Per-portal access constraints enforced by {@link PortalRateLimiter}.
     * Absent means no constraints (safe default for dev/mock portals).
     *
     * <ul>
     *   <li>{@code maxConcurrent} — maximum parallel agent runs against this
     *       portal. Set to 1 for shared-credential portals (one session).</li>
     *   <li>{@code minIntervalSeconds} — minimum wall-clock gap between
     *       consecutive runs. Prevents hammering the portal under burst load.</li>
     * </ul>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RateLimit(Integer maxConcurrent, Double minIntervalSeconds) {

        public int maxConcurrentOrDefault() {
            return maxConcurrent == null ? 1 : maxConcurrent;
        }

        public double minIntervalSecondsOrDefault() {
            return minIntervalSeconds == null ? 0.0 : minIntervalSeconds;
        }
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
