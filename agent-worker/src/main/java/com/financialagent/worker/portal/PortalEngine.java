package com.financialagent.worker.portal;

import com.microsoft.playwright.Page;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes a {@link PortalDescriptor}'s step list against a Playwright page.
 * Resolves {@code ${scope.key}} placeholders from a flat bindings map.
 * Emits a redacted action log via the supplied listener so the caller can
 * record it in the run manifest without ever seeing secret values.
 */
public final class PortalEngine {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private final Page page;
    private final Map<String, String> bindings;
    private final BiConsumer<String, String> auditListener;

    public PortalEngine(Page page,
                        Map<String, String> bindings,
                        BiConsumer<String, String> auditListener) {
        this.page = page;
        this.bindings = bindings;
        this.auditListener = auditListener;
    }

    public void run(PortalDescriptor descriptor) {
        for (PortalDescriptor.Step step : descriptor.steps()) {
            execute(descriptor.baseUrl(), step);
        }
    }

    private void execute(String baseUrl, PortalDescriptor.Step step) {
        switch (step.action()) {
            case navigate -> {
                String url = baseUrl + step.target();
                audit("navigate", url);
                page.navigate(url);
            }
            case fill -> {
                String resolved = resolve(step.value());
                audit("fill", step.selector()
                        + (step.redacted() ? " (value redacted)" : " = " + resolved));
                page.locator(step.selector()).fill(resolved);
            }
            case click -> {
                audit("click", step.selector());
                page.locator(step.selector()).click();
            }
            case waitForUrl -> {
                audit("waitForUrl", step.target());
                page.waitForURL(step.target());
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
}
