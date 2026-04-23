package com.neoproc.financialagent.testingharness.model;

import java.math.BigDecimal;

/**
 * Fixed employee record rendered by the mock payroll table. IDs are kept
 * in the hyphenated Costa Rica cédula format (e.g. "1-0909-0501") to
 * exercise the agent's ID-normalization path; some names carry diacritics
 * (ñ, á, í) so the agent's name-confirmation path can be tested against
 * transliterated canonical inputs.
 */
public record Employee(String id, String name, BigDecimal currentSalary) {}
