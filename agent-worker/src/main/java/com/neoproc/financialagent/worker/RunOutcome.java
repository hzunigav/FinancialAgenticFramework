package com.neoproc.financialagent.worker;

import java.nio.file.Path;

/**
 * Result returned by {@link PortalRunService#run} after a portal run
 * completes (successfully or not).
 *
 * @param runDir  directory containing all artifacts for this run
 *                ({@code manifest.json}, {@code network.har},
 *                {@code trace.zip}, {@code report.png}, and — for submit
 *                adapters — {@code payroll-submit-result.v1.json})
 * @param status  terminal run status: {@code SUCCESS}, {@code PARTIAL},
 *                {@code MISMATCH}, {@code FAILED}, or {@code SHADOW_HALT}
 */
public record RunOutcome(Path runDir, String status) {}
