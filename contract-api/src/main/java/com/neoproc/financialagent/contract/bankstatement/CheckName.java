package com.neoproc.financialagent.contract.bankstatement;

/**
 * The independent verification checks the agent reports and Praxis
 * re-asserts. See BankStatementUploadDesign §7.1.
 */
public enum CheckName {
    ORG_SELECTED,
    ACCOUNT_SELECTED,
    FILE_ACCEPTED,
    NO_DUPLICATE,
    ROW_COUNT,
    NET_MOVEMENT,
    OPENING_BALANCE,
    CLOSING_BALANCE
}
