package com.neoproc.financialagent.common.credentials;

import java.util.Map;

/**
 * Resolved credentials for a single portal. Values are returned as a flat
 * map keyed by the placeholder name used in portal descriptors (e.g.
 * {@code "credentials.username"}, {@code "credentials.password"}). The
 * provider that produced this record is responsible for source and
 * access-audit concerns; consumers only see the resolved values.
 */
public record PortalCredentials(String portalId, Map<String, String> values) {

    public String require(String key) {
        String v = values.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "Missing credential '" + key + "' for portal " + portalId);
        }
        return v;
    }
}
