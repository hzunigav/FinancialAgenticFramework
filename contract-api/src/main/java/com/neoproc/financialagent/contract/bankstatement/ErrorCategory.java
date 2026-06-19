package com.neoproc.financialagent.contract.bankstatement;

/**
 * Grouped failure taxonomy for the result envelope. {@code errorCategory}
 * is the primary/most-severe; {@link ErrorItem#category()} repeats one per
 * problem found. Required (non-null) whenever status != SUCCESS.
 *
 * <p>Only the access-group transient categories ({@link #XERO_UNREACHABLE},
 * {@link #SESSION_EXPIRED}, {@link #TIMEOUT}) are ever {@code retryable=true};
 * reconciliation failures always need a human.
 */
public enum ErrorCategory {
    // Access
    XERO_UNREACHABLE,
    LOGIN_FAILED,
    MFA_REQUIRED,
    SESSION_EXPIRED,
    ORG_NOT_FOUND,
    ACCOUNT_NOT_FOUND,
    // File / format
    EMPTY_FILE,
    FILE_CORRUPT,
    UNSUPPORTED_FORMAT,
    COLUMN_MAPPING_FAILED,
    // Upload
    UPLOAD_REJECTED,
    DUPLICATE_STATEMENT,
    PARTIAL_IMPORT,
    // Reconciliation
    COUNT_MISMATCH,
    NET_MOVEMENT_MISMATCH,
    OPENING_BALANCE_MISMATCH,
    CLOSING_BALANCE_MISMATCH,
    // Other
    TIMEOUT,
    UNKNOWN
}
