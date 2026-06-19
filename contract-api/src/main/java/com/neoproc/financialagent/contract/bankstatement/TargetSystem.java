package com.neoproc.financialagent.contract.bankstatement;

/**
 * Accounting system the statement is uploaded into. Provider-agnostic so
 * the same contract can later drive Zoho Books; the day-one target is XERO.
 * Serializes to the exact schema enum values (constant name == wire value).
 */
public enum TargetSystem {
    XERO,
    ZOHO_BOOKS
}
