package com.neoproc.financialagent.contract.bankstatement;

/**
 * Headline outcome of an upload. Precedence (see BankStatementUploadDesign
 * §7.2): FAILED &gt; MISMATCH &gt; PARTIAL &gt; SUCCESS. Praxis routes anything
 * other than {@link #SUCCESS} to HITL.
 */
public enum BankStatementStatus {
    /** Imported and every applicable check passed (or was skipped). */
    SUCCESS,
    /** Imported but incomplete — row count short / some rows rejected. */
    PARTIAL,
    /** Imported but a reconciliation check failed (balances / net movement / duplicate). */
    MISMATCH,
    /** Nothing imported — an access / account / file check failed. */
    FAILED
}
