package com.neoproc.financialagent.worker;

import java.nio.file.Path;

/**
 * Result returned by {@link PortalRunService#run} and {@link PortalRunService#runProbe}
 * after a portal run completes (successfully or not).
 *
 * @param runDir           directory containing all artifacts for this run
 *                         ({@code manifest.json}, {@code network.har},
 *                         {@code trace.zip}, {@code report.png}, and — for submit
 *                         adapters — {@code payroll-submit-result.v1.json})
 * @param status           terminal run status: {@code SUCCESS}, {@code PARTIAL},
 *                         {@code MISMATCH}, {@code FAILED}, or {@code SHADOW_HALT}
 * @param screenshotSha256 SHA-256 hex of the probe screenshot; {@code null} for
 *                         normal capture/submit runs or when the screenshot failed
 * @param artifactUri      S3 URI of the run's {@code manifest.json}; {@code null}
 *                         when no artifacts-bucket is configured (local dev) or
 *                         when the upload failed (artifacts only on local disk)
 */
public record RunOutcome(Path runDir, String status, String screenshotSha256, String artifactUri) {

    public RunOutcome(Path runDir, String status, String screenshotSha256) {
        this(runDir, status, screenshotSha256, null);
    }

    /** Convenience constructor for normal (non-probe) runs. */
    public RunOutcome(Path runDir, String status) {
        this(runDir, status, null, null);
    }
}
