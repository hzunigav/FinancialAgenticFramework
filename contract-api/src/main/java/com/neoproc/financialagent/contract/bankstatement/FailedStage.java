package com.neoproc.financialagent.contract.bankstatement;

/**
 * Which stage of the Xero upload the run failed at. Null on SUCCESS.
 */
public enum FailedStage {
    AUTH,
    ORG_SELECT,
    ACCOUNT_SELECT,
    UPLOAD,
    VERIFY
}
