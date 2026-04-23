package com.neoproc.financialagent.worker;

import java.util.Map;

/**
 * Shared helpers for {@link PortalAdapter} implementations. Adapters that
 * emit envelopes should extend {@link AbstractCaptureAdapter} or
 * {@link AbstractSubmitAdapter} (both of which extend this). Adapters that
 * don't emit envelopes (dev-only, no source-of-truth) can extend this
 * directly or implement {@link PortalAdapter} themselves.
 */
abstract class BaseAdapter implements PortalAdapter {

    /** Reads a required binding or throws with a deploy-friendly error. */
    protected static String require(Map<String, String> bindings, String key) {
        String v = bindings.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "Missing runtime binding " + key
                            + " (pass via -D" + key + "=<value>)");
        }
        return v;
    }
}
