package com.neoproc.financialagent.contract.bankstatement;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * Cleartext routing metadata present in every bank-statement envelope
 * (request and result echo it verbatim — the {@code expected*} values are
 * what the result's checks compare against).
 *
 * <p>Org selection (Phase-0 finding): the Xero UI switches org by a
 * <b>short-code</b>, not the tenant UUID. {@link #xeroShortCode} (e.g.
 * {@code "!0X0!!"}) is the deterministic selector the agent deep-links to
 * ({@code go.xero.com/app/!<shortcode>/...}); {@link #xeroOrgName} is the
 * fallback (matched in the org switcher) and human label; {@link #xeroOrgUuid}
 * is the API-side cross-check (it is never a UI selector). The agent matches
 * the account by {@link #bankAccountNumber} (+ {@link #iban} /
 * {@link #currency} to disambiguate).
 *
 * <p>Monetary fields are decimal <b>strings</b> (e.g. {@code "1279.50"}),
 * never JSON numbers — the schema pattern {@code ^-?\\d+(\\.\\d+)?$} rejects
 * Jackson's default numeric BigDecimal serialization, so these stay String
 * end-to-end to avoid the producer trap and float drift.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BankStatementTask(
        String type,
        String operation,
        TargetSystem targetSystem,
        String xeroOrgUuid,
        String xeroOrgName,
        String xeroShortCode,
        String clientName,
        String bankName,
        String bankAccountNumber,
        String iban,
        String currency,
        LocalDate periodStart,
        LocalDate periodEnd,
        String fileName,
        int expectedRowCount,
        String expectedNetMovement,
        String expectedOpeningBalance,
        String expectedClosingBalance) {

    /** Schema const for {@code task.type}. */
    public static final String TYPE = "bank-statement-upload";
    /** Schema const for {@code task.operation}. */
    public static final String OPERATION = "import";
}
