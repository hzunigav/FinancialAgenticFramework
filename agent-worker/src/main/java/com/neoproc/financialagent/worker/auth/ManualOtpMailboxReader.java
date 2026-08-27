package com.neoproc.financialagent.worker.auth;

import java.util.function.Function;

/**
 * An {@link OtpMailboxReader} that asks a human for the code instead of reading
 * a mailbox. Two uses:
 * <ul>
 *   <li><b>Headed dry-runs</b> — validate the declarative {@code emailOtp} login
 *       path before a service mailbox is provisioned: the operator reads the
 *       email and types the code.</li>
 *   <li><b>HITL fallback</b> — the escape hatch when automated retrieval is
 *       unavailable (SMS-only account, mailbox unreachable). Wire the prompt to
 *       a Flowable/Praxis user task rather than stdin.</li>
 * </ul>
 * The prompt function receives a human-readable message and returns the entered
 * code (or null/blank to signal "no code", which surfaces as a retryable AUTH
 * failure via {@link OtpUnavailableException}).
 */
public final class ManualOtpMailboxReader implements OtpMailboxReader {

    private final Function<String, String> prompt;

    public ManualOtpMailboxReader(Function<String, String> prompt) {
        this.prompt = prompt;
    }

    @Override
    public String awaitCode(OtpQuery query) {
        String message = "Enter the OTP code emailed by " + query.fromContains()
                + " (subject ~ \"" + query.subjectContains() + "\")";
        String code = prompt.apply(message);
        if (code == null || code.isBlank()) {
            throw new OtpUnavailableException("No OTP code was entered manually");
        }
        return code.trim();
    }
}
