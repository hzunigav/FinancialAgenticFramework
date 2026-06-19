package com.neoproc.financialagent.contract.bankstatement;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A CSV row Xero did not import (e.g. flagged as a possible duplicate).
 * {@code rowNumber} is 1-based over the CSV data rows.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RejectedRow(
        int rowNumber,
        String rawLine,
        String reason) {}
