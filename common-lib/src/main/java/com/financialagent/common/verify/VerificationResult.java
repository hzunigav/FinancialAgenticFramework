package com.financialagent.common.verify;

import java.util.List;

public record VerificationResult<T>(Status status, T source, T scraped, List<FieldDiff> diffs) {

    public enum Status { MATCH, MISMATCH }

    public boolean matched() {
        return status == Status.MATCH;
    }
}
