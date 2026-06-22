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
        SecurityContext securityContext,
        // Which task flows this portal handles. Trailing position so the
        // existing positional constructor in tests only needs a null appended.
        List<String> flows) {

    public static final String SCOPE_PER_FIRM = "per-firm";
    public static final String SCOPE_SHARED = "shared";
    public static final String SCOPE_PER_CLIENT = "per-client";

    public static final String FLOW_CAPTURE = "capture";
    public static final String FLOW_SUBMIT = "submit";
    public static final String FLOW_BANKSTATEMENT = "bankstatement";

    /**
     * Which task flows this portal handles — drives which {@code @SqsListener}
     * the worker registers (and therefore which task queues it expects to
     * exist). Absent → both {@code capture} and {@code submit} (back-compat:
     * the pre-{@code flows} behaviour where every worker registered both).
     *
     * <p>A submit-only portal (e.g. INS RT-Virtual, whose capture is owned by
     * AutoPlanilla) declares {@code flows: [submit]} so the worker never tries
     * to resolve a capture queue that was never provisioned — which under the
     * {@code QueueNotFoundStrategy.FAIL} policy would abort the whole Spring
     * context and leave the submit listener dead.
     */
    public List<String> flows() {
        return flows == null || flows.isEmpty()
                ? List.of(FLOW_CAPTURE, FLOW_SUBMIT)
                : flows;
    }

    public boolean handlesCapture() {
        return flows().contains(FLOW_CAPTURE);
    }

    public boolean handlesSubmit() {
        return flows().contains(FLOW_SUBMIT);
    }

    /**
     * Bank-statement upload flow (Xero). A bankstatement-only portal must
     * declare {@code flows: [bankstatement]} explicitly — otherwise the empty
     * default ({@code [capture, submit]}) would register the payroll listeners
     * and never the bank-statement one.
     */
    public boolean handlesBankStatement() {
        return flows().contains(FLOW_BANKSTATEMENT);
    }

    public boolean isShadowMode() {
        return Boolean.TRUE.equals(shadowMode);
    }

    /**
     * How portal credentials are scoped at the source of truth. Drives the
     * secret path the credentials provider reads from. Three values:
     * <ul>
     *   <li>{@code per-firm} — one login per firm. Path:
     *       {@code financeagent/firms/<firmId>/portals/<portalId>}.</li>
     *   <li>{@code shared} — one tenant-wide login that fronts every firm.
     *       Path: {@code financeagent/shared/portals/<portalId>}. The
     *       per-firm portal-side selector rides on
     *       {@code task.clientIdentifier}.</li>
     *   <li>{@code per-client} — one login per (firm × client). The login
     *       itself is bound to a corporate id, so the same firm can own
     *       many client logins. Path:
     *       {@code financeagent/firms/<firmId>/portals/<portalId>/<clientIdentifier>}.
     *       {@code task.clientIdentifier} is required and used both as the
     *       secret-path segment and (where applicable) for portal-side
     *       verification.</li>
     * </ul>
     * Defaults to per-firm — the safer assumption for a new portal, since
     * leaking shared credentials to the wrong tenant is the worse failure
     * mode.
     */
    public String credentialScope() {
        return credentialScope == null ? SCOPE_PER_FIRM : credentialScope;
    }

    public boolean hasSharedCredentials() {
        return SCOPE_SHARED.equals(credentialScope());
    }

    public boolean hasPerClientCredentials() {
        return SCOPE_PER_CLIENT.equals(credentialScope());
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
            Integer maxIterations,
            // For action=select: "label" (default) or "value". Pick by visible
            // option text vs the underlying <option value="..."> attribute.
            // Use "value" when the visible label is operator-renameable but the
            // value is a stable id. Trailing position so existing positional
            // Step(...) constructors only need a null appended.
            String match) {

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
        navigate, fill, click, waitForUrl, waitForSelector, select, pause, totp, forEach,
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
         *
         * <p>{@code pagination} is optional; absent → single-page scrape.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record RowSpec(String selector,
                              Map<String, String> columns,
                              Pagination pagination) {}

        /**
         * Optional pagination for a row-mode scrape. When present the scraper
         * walks every page instead of just the visible one: scrape page →
         * click {@code nextSelector} → wait for {@code rangeSelector}'s text to
         * advance → repeat until the next control is absent or disabled.
         *
         * <ul>
         *   <li>{@code nextSelector} — control that advances to the next page.
         *       When it matches nothing, or matches a disabled element, the
         *       scrape stops — so a not-yet-verified selector degrades safely
         *       to today's single-page behaviour.</li>
         *   <li>{@code rangeSelector} — element whose text changes per page
         *       (e.g. the "1-10 of 92" counter). Used as the page-advanced
         *       signal after a next-click; omit to fall back to a fixed settle.</li>
         *   <li>{@code dedupeBy} — row column used to de-duplicate across pages
         *       (default {@code id}); guards against a re-render race that
         *       re-scrapes a page from double-counting.</li>
         *   <li>{@code maxPages} — safety cap on the page loop (default 100).</li>
         * </ul>
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Pagination(String nextSelector,
                                 String rangeSelector,
                                 String dedupeBy,
                                 Integer maxPages) {

            public String dedupeByOrDefault() {
                return dedupeBy == null || dedupeBy.isBlank() ? "id" : dedupeBy;
            }

            public int maxPagesOrDefault() {
                return maxPages == null ? 100 : maxPages;
            }
        }
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
