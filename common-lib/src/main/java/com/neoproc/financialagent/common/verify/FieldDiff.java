package com.neoproc.financialagent.common.verify;

public record FieldDiff(String field, Object source, Object scraped) {}
