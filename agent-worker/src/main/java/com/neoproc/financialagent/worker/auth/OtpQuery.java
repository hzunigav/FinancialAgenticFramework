package com.neoproc.financialagent.worker.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A request to retrieve a one-time 2FA code from a mailbox — the portal-agnostic
 * shape the {@link OtpMailboxReader} polls against. Everything here is
 * non-secret filter/config (the mailbox connection lives in the reader), so a
 * descriptor's {@code emailOtp} step can carry it verbatim.
 *
 * <p>Matching is intentionally narrow to avoid ever typing a stale or unrelated
 * code: a candidate message must be <b>from</b> {@code fromContains}, have a
 * subject containing {@code subjectContains}, and have arrived at/after
 * {@code notBefore} (the moment we triggered the send, minus a small skew). The
 * code itself is group 1 of {@code codePattern}.
 *
 * <p>Example (Hacienda OVI): from {@code tribucrcorreo@hacienda.go.cr}, subject
 * {@code "Validación de los usuarios de servicios"}, body
 * {@code "…el código de validación es: 14528."}, pattern
 * {@code código de validación es:\s*(\d{4,6})}.
 */
public record OtpQuery(
        String fromContains,
        String subjectContains,
        Pattern codePattern,
        Instant notBefore,
        Duration timeout,
        Duration pollInterval) {

    public OtpQuery {
        if (codePattern == null) {
            throw new IllegalArgumentException("OtpQuery requires a codePattern");
        }
        if (codePattern.matcher("").groupCount() < 1) {
            throw new IllegalArgumentException(
                    "OtpQuery.codePattern must have a capturing group for the code, got: "
                            + codePattern.pattern());
        }
        if (notBefore == null) {
            throw new IllegalArgumentException("OtpQuery requires a notBefore cutoff");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("OtpQuery requires a positive timeout");
        }
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("OtpQuery requires a positive pollInterval");
        }
    }

    /**
     * Extracts the code (group 1 of {@code codePattern}) from a message's text,
     * or empty if the pattern does not match. Pure — no I/O — so it can be
     * unit-tested against a real OTP email body without a mailbox.
     */
    public Optional<String> extractCode(String messageText) {
        if (messageText == null) {
            return Optional.empty();
        }
        Matcher m = codePattern.matcher(messageText);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** True if a message's From header satisfies the sender filter (case-insensitive). */
    public boolean fromMatches(String fromHeader) {
        return fromContains == null || fromContains.isBlank()
                || (fromHeader != null && fromHeader.toLowerCase().contains(fromContains.toLowerCase()));
    }

    /** True if a message's subject satisfies the subject filter (case-insensitive). */
    public boolean subjectMatches(String subject) {
        return subjectContains == null || subjectContains.isBlank()
                || (subject != null && subject.toLowerCase().contains(subjectContains.toLowerCase()));
    }

    /** True if a message received at {@code receivedAt} is fresh enough to trust. */
    public boolean freshEnough(Instant receivedAt) {
        return receivedAt != null && !receivedAt.isBefore(notBefore);
    }
}
