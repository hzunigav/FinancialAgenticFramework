package com.neoproc.financialagent.worker.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks {@link TotpGenerator} against the RFC 6238 Appendix-B SHA-1 test
 * vectors (ASCII seed "12345678901234567890", base32
 * {@code GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ}, 8 digits, 30s step). If these
 * pass, the algorithm matches what Google Authenticator / Xero expect.
 */
class TotpGeneratorTest {

    /** RFC 6238 §B seed, base32-encoded. */
    private static final String SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @ParameterizedTest(name = "T={0}s -> {1}")
    @CsvSource({
        "59,          94287082",
        "1111111109,  07081804",
        "1111111111,  14050471",
        "1234567890,  89005924",
        "2000000000,  69279037"
    })
    @DisplayName("RFC 6238 SHA-1 8-digit vectors")
    void rfc6238Sha1Vectors(long epochSeconds, String expected) {
        assertEquals(expected, TotpGenerator.generate(SEED, epochSeconds, 8, 30, "HmacSHA1"));
    }

    @Test
    @DisplayName("6-digit code is the last 6 digits of the 8-digit truncation (T=59)")
    void sixDigitMatchesTail() {
        assertEquals("287082", TotpGenerator.generate(SEED, 59, 6, 30, "HmacSHA1"));
    }

    @Test
    @DisplayName("base32 decode matches the known ASCII seed and tolerates spaces/case")
    void base32DecodeTolerant() {
        byte[] expected = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertEquals(new String(expected, StandardCharsets.US_ASCII),
                new String(TotpGenerator.base32Decode("gezd gnbv gy3t qojq gezd gnbv gy3t qojq"),
                        StandardCharsets.US_ASCII));
    }
}
