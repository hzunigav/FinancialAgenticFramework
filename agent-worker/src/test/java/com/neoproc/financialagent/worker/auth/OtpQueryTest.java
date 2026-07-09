package com.neoproc.financialagent.worker.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the OTP matching/extraction logic against the real Hacienda OVi email
 * sample so a regex or filter regression is caught without a live mailbox. The
 * body below is the Phase-0 capture verbatim.
 */
class OtpQueryTest {

    // The exact code line Hacienda OVi sends (Phase-0 spike, otp-email.txt).
    private static final String OVI_BODY =
            "El Ministerio de Hacienda le informa que el código de validación es: 14528.";
    private static final Pattern OVI_PATTERN =
            Pattern.compile("código de validación es:\\s*(\\d{4,6})");

    private static OtpQuery oviQuery() {
        return new OtpQuery(
                "tribucrcorreo@hacienda.go.cr",
                "Validación de los usuarios de servicios",
                OVI_PATTERN,
                Instant.parse("2026-07-09T00:00:00Z"),
                Duration.ofSeconds(120),
                Duration.ofSeconds(4));
    }

    @Test
    void extractsCodeFromRealOviBody() {
        assertEquals("14528", oviQuery().extractCode(OVI_BODY).orElseThrow());
    }

    @Test
    void extractsCodeFromHtmlWrappedBody() {
        String html = "<p>El Ministerio de Hacienda le informa que el "
                + "código de validación es: 90321.</p>";
        assertEquals("90321", oviQuery().extractCode(html).orElseThrow());
    }

    @Test
    void returnsEmptyWhenNoCodePresent() {
        assertTrue(oviQuery().extractCode("Su sesión ha expirado.").isEmpty());
        assertTrue(oviQuery().extractCode(null).isEmpty());
    }

    @Test
    void fromMatch_isCaseInsensitiveSubstring() {
        OtpQuery q = oviQuery();
        assertTrue(q.fromMatches("Ministerio de Hacienda <TribuCRcorreo@Hacienda.go.cr>"));
        assertFalse(q.fromMatches("phish@evil.example"));
        assertFalse(q.fromMatches(null));
    }

    @Test
    void subjectMatch_isCaseInsensitiveSubstring() {
        OtpQuery q = oviQuery();
        assertTrue(q.subjectMatches("Validación de los usuarios de servicios"));
        assertFalse(q.subjectMatches("Boletín informativo"));
    }

    @Test
    void freshEnough_rejectsMailBeforeCutoff() {
        OtpQuery q = oviQuery();   // cutoff 2026-07-09T00:00:00Z
        assertTrue(q.freshEnough(Instant.parse("2026-07-09T00:00:01Z")));
        assertTrue(q.freshEnough(Instant.parse("2026-07-09T00:00:00Z")));   // boundary inclusive
        assertFalse(q.freshEnough(Instant.parse("2026-07-08T23:59:59Z")));
        assertFalse(q.freshEnough(null));
    }

    @Test
    void constructor_rejectsPatternWithoutCaptureGroup() {
        assertThrows(IllegalArgumentException.class, () -> new OtpQuery(
                "x", "y", Pattern.compile("\\d{4,6}"),   // no capturing group
                Instant.now(), Duration.ofSeconds(30), Duration.ofSeconds(2)));
    }

    @Test
    void constructor_rejectsNonPositiveTimeoutAndPoll() {
        assertThrows(IllegalArgumentException.class, () -> new OtpQuery(
                "x", "y", OVI_PATTERN, Instant.now(), Duration.ZERO, Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new OtpQuery(
                "x", "y", OVI_PATTERN, Instant.now(), Duration.ofSeconds(30), Duration.ZERO));
    }
}
