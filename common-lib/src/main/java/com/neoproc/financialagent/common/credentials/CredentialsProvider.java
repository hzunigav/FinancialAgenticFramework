package com.neoproc.financialagent.common.credentials;

/**
 * Source of portal credentials. The interface is intentionally tiny so that
 * a file-based dev implementation and an AWS Secrets Manager prod
 * implementation can swap without changing caller code. Placeholder
 * resolution in {@code PortalEngine} consumes the returned map directly.
 *
 * <p>{@code clientId} is the per-client identifier (cédula jurídica /
 * corporate id) for portals declaring {@code credentialScope: per-client}.
 * Pass {@code null} for shared and per-firm portals — implementations
 * resolve a single secret per portal in those scopes.
 */
public interface CredentialsProvider {

    PortalCredentials get(String portalId, String clientId);

    /** Convenience for shared and per-firm portals where no client id is needed. */
    default PortalCredentials get(String portalId) {
        return get(portalId, null);
    }

    /**
     * Normalises a client identifier to the canonical form used in credential
     * storage paths (digits-only for cédula jurídica, e.g. {@code "3-101-680139"}
     * → {@code "3101680139"}).
     *
     * <p>Praxis emits the dashed legal form ({@code 3-101-680139}); secret keys
     * in {@code secrets.properties} and AWS Secrets Manager paths use the
     * dash-free internal id ({@code 3101680139}). Implementations call this
     * before composing the lookup key so either form on the wire resolves to
     * the same secret.
     *
     * <p>Strips ASCII hyphens and whitespace; preserves all other characters
     * verbatim so non-cédula identifiers (UUIDs, alphanumeric tenant ids) pass
     * through unchanged.
     */
    static String normalizeClientId(String clientId) {
        if (clientId == null) return null;
        StringBuilder out = new StringBuilder(clientId.length());
        for (int i = 0; i < clientId.length(); i++) {
            char c = clientId.charAt(i);
            if (c == '-' || Character.isWhitespace(c)) continue;
            out.append(c);
        }
        return out.toString();
    }
}
