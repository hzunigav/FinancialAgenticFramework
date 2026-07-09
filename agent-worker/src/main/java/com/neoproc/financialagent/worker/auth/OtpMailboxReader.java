package com.neoproc.financialagent.worker.auth;

/**
 * Retrieves an out-of-band one-time 2FA code from a mailbox — the seam behind
 * the engine's {@code emailOtp} action. Portals whose second factor is a
 * server-generated code delivered by email (e.g. Hacienda OVI) can be driven
 * declaratively: the descriptor's {@code emailOtp} step supplies the
 * {@link OtpQuery} filters + the target field, and an injected reader does the
 * fetch. Kept an interface so the concrete mailbox (which IMAP account / API)
 * stays a deployment decision, and so tests + headed dry-runs can substitute a
 * fake or a manual-entry reader.
 *
 * <p>Contrast with TOTP (Xero): there the code is generated from a held seed and
 * needs no reader; here the code exists only in an inbox and must be retrieved.
 */
public interface OtpMailboxReader {

    /**
     * Blocks until a message matching {@code query} arrives (from/subject/
     * freshness all satisfied) and returns its extracted code, or throws
     * {@link OtpUnavailableException} once {@code query.timeout()} elapses with
     * no match. Implementations should consume the message (mark it read /
     * delete) so the same code is never returned twice.
     */
    String awaitCode(OtpQuery query);

    /**
     * Thrown when no matching code arrived within the timeout, or the mailbox
     * could not be reached. Signals a transient AUTH failure to the caller —
     * the run should emit a retryable result, not hard-fail.
     */
    class OtpUnavailableException extends RuntimeException {
        public OtpUnavailableException(String message) {
            super(message);
        }

        public OtpUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
