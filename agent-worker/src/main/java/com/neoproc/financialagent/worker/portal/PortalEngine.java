package com.neoproc.financialagent.worker.portal;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.SelectOption;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes a {@link PortalDescriptor}'s step list against a Playwright page.
 * Resolves {@code ${scope.key}} placeholders from a mutable bindings map —
 * the {@code pause} step can inject new bindings at run time (e.g., an SMS
 * code from the operator). Emits a redacted action log via the supplied
 * listener so the caller can record it in the run manifest without ever
 * seeing secret values.
 */
public final class PortalEngine {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private final Page page;
    private final Map<String, String> bindings;
    private final Map<String, List<Map<String, String>>> listBindings;
    private final BiConsumer<String, String> auditListener;
    private final Function<String, String> operatorInput;
    private final boolean shadowMode;

    public PortalEngine(Page page,
                        Map<String, String> bindings,
                        BiConsumer<String, String> auditListener,
                        Function<String, String> operatorInput,
                        boolean shadowMode) {
        this(page, bindings, new HashMap<>(), auditListener, operatorInput, shadowMode);
    }

    /**
     * Full constructor including the list-bindings map. List bindings
     * feed the {@code forEach} action; keeping them in a separate map
     * from the flat {@code ${scope.key}} bindings avoids jamming two
     * value shapes ({@code String} vs {@code List<Map<String,String>>})
     * into one map and keeps resolve-time type-safety trivial.
     */
    public PortalEngine(Page page,
                        Map<String, String> bindings,
                        Map<String, List<Map<String, String>>> listBindings,
                        BiConsumer<String, String> auditListener,
                        Function<String, String> operatorInput,
                        boolean shadowMode) {
        this.page = page;
        this.bindings = bindings;
        this.listBindings = listBindings;
        this.auditListener = auditListener;
        this.operatorInput = operatorInput;
        this.shadowMode = shadowMode;
    }

    public void runSteps(String baseUrl, List<PortalDescriptor.Step> steps) {
        for (PortalDescriptor.Step step : steps) {
            execute(baseUrl, step);
        }
    }

    private void execute(String baseUrl, PortalDescriptor.Step step) {
        if (step.isSubmit() && shadowMode) {
            audit("BLOCKED-SUBMIT", step.selector() != null ? step.selector() : String.valueOf(step.action()));
            throw new ShadowHalt(
                    "Shadow mode blocked a submit step: "
                            + (step.selector() != null ? step.selector() : step.action()));
        }
        switch (step.action()) {
            case navigate -> {
                String url = baseUrl + step.target();
                audit("navigate", url);
                page.navigate(url);
            }
            case fill -> {
                String resolvedSelector = resolve(step.selector());
                String resolved = resolve(step.value());
                audit("fill", resolvedSelector
                        + (step.redacted() ? " (value redacted)" : " = " + resolved));
                page.locator(resolvedSelector).fill(resolved);
            }
            case click -> {
                String resolvedSelector = resolve(step.selector());
                audit("click", resolvedSelector);
                page.locator(resolvedSelector).click();
            }
            case waitForUrl -> {
                audit("waitForUrl", step.target());
                page.waitForURL(step.target());
            }
            case waitForSelector -> {
                String resolvedSelector = resolve(step.selector());
                audit("waitForSelector", resolvedSelector);
                page.waitForSelector(resolvedSelector);
            }
            case select -> {
                String resolvedSelector = resolve(step.selector());
                String resolved = resolve(step.value());
                audit("select", resolvedSelector + " = " + resolved);
                page.locator(resolvedSelector).selectOption(new SelectOption().setLabel(resolved));
            }
            case pause -> {
                String bindTo = step.bindTo();
                if (bindTo == null || bindTo.isBlank()) {
                    throw new IllegalStateException("pause step requires a bindTo key");
                }
                String prompt = step.prompt() != null ? resolve(step.prompt()) : "operator input";
                audit("pause", "prompt=\"" + prompt + "\" bindTo=" + bindTo);
                String response = operatorInput.apply(prompt);
                if (response == null) {
                    throw new IllegalStateException(
                            "pause step received no input for bindTo=" + bindTo);
                }
                bindings.put(bindTo, response);
                audit("bind", bindTo + " (value redacted)");
            }
            case forEach -> {
                String over = step.over();
                String item = step.item();
                if (over == null || over.isBlank()) {
                    throw new IllegalStateException("forEach step requires an 'over' key");
                }
                if (item == null || item.isBlank()) {
                    throw new IllegalStateException("forEach step requires an 'item' scope name");
                }
                List<Map<String, String>> rows = listBindings.get(over);
                if (rows == null) {
                    throw new IllegalStateException(
                            "forEach 'over' references unknown list binding: " + over);
                }
                audit("forEach", "over=" + over + " item=" + item + " rows=" + rows.size());
                List<PortalDescriptor.Step> substeps = step.steps();
                for (Map<String, String> row : rows) {
                    List<String> pushed = new ArrayList<>(row.size());
                    for (Map.Entry<String, String> e : row.entrySet()) {
                        String scopedKey = item + "." + e.getKey();
                        bindings.put(scopedKey, e.getValue());
                        pushed.add(scopedKey);
                    }
                    try {
                        for (PortalDescriptor.Step sub : substeps) {
                            execute(baseUrl, sub);
                        }
                    } finally {
                        for (String key : pushed) {
                            bindings.remove(key);
                        }
                    }
                }
            }
        }
    }

    private void audit(String action, String target) {
        if (auditListener != null) {
            auditListener.accept(action, target);
        }
    }

    private String resolve(String raw) {
        if (raw == null) return null;
        Matcher m = PLACEHOLDER.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = bindings.get(key);
            if (value == null) {
                throw new IllegalStateException("Unresolved portal binding: " + key);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Thrown when a submit-flagged step is reached while shadowMode is on. */
    public static final class ShadowHalt extends RuntimeException {
        public ShadowHalt(String message) { super(message); }
    }
}
