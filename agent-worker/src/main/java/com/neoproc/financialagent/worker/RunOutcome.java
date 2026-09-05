package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.worker.artifact.S3ArtifactStore;

import java.nio.file.Path;
import java.util.List;

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
 * @param artifactFolder   S3 URI of the run's folder, trailing slash included;
 *                         null under the same conditions as {@code artifactUri}.
 *                         Handed to consumers so they can address the run's
 *                         output without reconstructing the key layout.
 * @param artifacts        every object uploaded, relative name and absolute URI.
 *                         Empty when nothing was uploaded — never null, so
 *                         callers can iterate without a null check.
 */
public record RunOutcome(Path runDir,
                         String status,
                         String screenshotSha256,
                         String artifactUri,
                         String artifactFolder,
                         List<S3ArtifactStore.StoredArtifact> artifacts) {

    public RunOutcome {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public RunOutcome(Path runDir, String status, String screenshotSha256, String artifactUri) {
        this(runDir, status, screenshotSha256, artifactUri, null, List.of());
    }

    public RunOutcome(Path runDir, String status, String screenshotSha256) {
        this(runDir, status, screenshotSha256, null, null, List.of());
    }

    /** Convenience constructor for normal (non-probe) runs. */
    public RunOutcome(Path runDir, String status) {
        this(runDir, status, null, null, null, List.of());
    }
}
