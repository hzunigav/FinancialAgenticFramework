package com.neoproc.financialagent.common.match;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;

/**
 * Fuzzy match between a canonical employee record (what the agent knows
 * is true) and the rows a portal displays. Portals disagree with us on
 * formatting — Costa Rica IDs show as {@code "1-0909-0501"} on one site
 * and {@code "109090501"} on another; names get transliterated
 * ({@code "Núñez"} → {@code "Nunez"}), truncated, or simply abbreviated.
 *
 * <p>Match strategy (per business call — match by ID, confirm by name):
 * <ol>
 *   <li>{@link #matchById} finds the display row whose ID matches the
 *       canonical ID after stripping non-digits.</li>
 *   <li>{@link #confirmName} checks that the matched row's displayed
 *       name plausibly corresponds to the canonical name, as a safety
 *       net against portal data drift (ID reassignment, typo'd cell).</li>
 * </ol>
 * Both steps together: {@link #match}.
 *
 * <p>No LLM involvement — diacritic-folding + digit-stripping is enough
 * to handle every real case we've seen, and keeps the match decision
 * auditable.
 */
public final class EmployeeMatcher {

    private EmployeeMatcher() {}

    /** Keep only ASCII digits; strips hyphens, spaces, letters, anything else. */
    public static String normalizeId(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Lower-case, diacritic-free, whitespace-collapsed. {@code "María Núñez"}
     * → {@code "maria nunez"}; covers the {@code ñ → n} and {@code é → e}
     * cases the caller asked about.
     */
    public static String normalizeName(String raw) {
        if (raw == null) return "";
        String decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        boolean prevSpace = true;
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            if (Character.isWhitespace(c)) {
                if (!prevSpace) {
                    sb.append(' ');
                    prevSpace = true;
                }
                continue;
            }
            sb.append(Character.toLowerCase(c));
            prevSpace = false;
        }
        // trim trailing space
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }

    /**
     * Index of the first displayed row whose ID matches {@code canonicalId}
     * after normalization, or empty if none match.
     */
    public static Optional<Integer> matchById(String canonicalId, List<String> displayedIds) {
        String target = normalizeId(canonicalId);
        if (target.isEmpty()) return Optional.empty();
        for (int i = 0; i < displayedIds.size(); i++) {
            if (target.equals(normalizeId(displayedIds.get(i)))) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    /**
     * Plausibility check. True iff, after normalization, one name is a
     * prefix of the other OR every whitespace-separated token of the
     * shorter name appears in the longer name. Intentionally lenient —
     * the ID match is the authoritative link; this is a guardrail, not
     * the primary matcher.
     */
    public static boolean confirmName(String canonicalName, String displayedName) {
        String a = normalizeName(canonicalName);
        String b = normalizeName(displayedName);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        if (a.startsWith(b) || b.startsWith(a)) return true;

        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        for (String token : shorter.split(" ")) {
            if (token.isEmpty()) continue;
            if (!longer.contains(token)) return false;
        }
        return true;
    }

    /**
     * Convenience: run {@link #matchById} and {@link #confirmName} in
     * sequence. Returns the matched index only if both checks pass.
     */
    public static Optional<Integer> match(String canonicalId,
                                          String canonicalName,
                                          List<String> displayedIds,
                                          List<String> displayedNames) {
        if (displayedIds.size() != displayedNames.size()) {
            throw new IllegalArgumentException(
                    "displayedIds and displayedNames must be the same length: "
                            + displayedIds.size() + " vs " + displayedNames.size());
        }
        Optional<Integer> byId = matchById(canonicalId, displayedIds);
        if (byId.isEmpty()) return Optional.empty();
        int idx = byId.get();
        return confirmName(canonicalName, displayedNames.get(idx))
                ? byId
                : Optional.empty();
    }
}
