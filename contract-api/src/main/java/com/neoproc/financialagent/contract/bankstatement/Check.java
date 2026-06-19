package com.neoproc.financialagent.contract.bankstatement;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One verification check the agent ran, carrying {@code expected} vs
 * {@code observed} so a human can see exactly what diverged. Skipped checks
 * (e.g. a balance whose expected value was null) are omitted from the array.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Check(
        CheckName name,
        boolean passed,
        String expected,
        String observed,
        String detail) {

    public static Check passed(CheckName name, String expected, String observed) {
        return new Check(name, true, expected, observed, null);
    }

    public static Check failed(CheckName name, String expected, String observed, String detail) {
        return new Check(name, false, expected, observed, detail);
    }
}
