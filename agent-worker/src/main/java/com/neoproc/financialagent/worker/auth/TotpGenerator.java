package com.neoproc.financialagent.worker.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;

/**
 * RFC 6238 TOTP generator for the shared Xero UI login's 2FA.
 *
 * <p>The base32 TOTP seed is stored alongside the Xero credential in
 * Secrets Manager; the worker computes the current 6-digit code at login
 * time (no external dependency — HMAC via the JDK, base32 decoded here).
 *
 * <p>Phase-0 confirmed Xero's 2FA is a standard 6-digit TOTP prompt with a
 * "Trust this device" option; this drives the descriptor's TOTP auth step.
 */
public final class TotpGenerator {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpGenerator() {}

    /** Current 6-digit SHA-1 TOTP for {@code base32Secret} (30s step). */
    public static String now(String base32Secret) {
        return generate(base32Secret, Instant.now().getEpochSecond(), 6, 30, "HmacSHA1");
    }

    /**
     * Computes a TOTP code per RFC 6238.
     *
     * @param base32Secret the shared secret, base32-encoded (spaces/dashes/
     *                     padding tolerated)
     * @param epochSeconds Unix time in seconds
     * @param digits       code length (6 for authenticator apps; 8 for the
     *                     RFC test vectors)
     * @param periodSeconds time step (typically 30)
     * @param hmacAlgo      JCE MAC algorithm (e.g. {@code HmacSHA1})
     */
    public static String generate(String base32Secret, long epochSeconds,
                                  int digits, int periodSeconds, String hmacAlgo) {
        byte[] key = base32Decode(base32Secret);
        long counter = Math.floorDiv(epochSeconds, periodSeconds);
        byte[] message = ByteBuffer.allocate(Long.BYTES).putLong(counter).array();

        byte[] hash;
        try {
            Mac mac = Mac.getInstance(hmacAlgo);
            mac.init(new SecretKeySpec(key, "RAW"));
            hash = mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP HMAC failed (" + hmacAlgo + "): " + e.getMessage(), e);
        }

        // RFC 4226 dynamic truncation.
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int otp = binary % (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", otp);
    }

    /** Decodes a base32 (RFC 4648) secret; tolerant of spaces, dashes, padding, case. */
    static byte[] base32Decode(String secret) {
        String clean = secret.trim()
                .replace(" ", "").replace("-", "")
                .toUpperCase()
                .replace("=", "");
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0, bits = 0, index = 0;
        for (int i = 0; i < clean.length(); i++) {
            int v = BASE32_ALPHABET.indexOf(clean.charAt(i));
            if (v < 0) {
                throw new IllegalArgumentException("Invalid base32 character: '" + clean.charAt(i) + "'");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >>> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out;
    }
}
