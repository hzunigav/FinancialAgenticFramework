package com.neoproc.financialagent.worker.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Uploads the contents of a finished run directory to S3 so artifacts
 * (manifest.json, report.png, network.har, trace.zip, the encrypted
 * envelope, etc.) survive Fargate task shutdown.
 *
 * <p>Layout in the bucket is flat per run, matching the on-disk layout
 * since {@link com.neoproc.financialagent.worker.PortalRunService} writes
 * everything directly into {@code runDir} with no subdirectories
 * (excepting the optional {@code fixtures/} dir for dev captures):
 *
 * <pre>
 *   s3://{bucket}/{envPrefix}/runs/{portalId}/{runId}/{filename}
 * </pre>
 *
 * <p>If the configured bucket is blank/absent the upload is skipped with a
 * single WARN log line — local development runs against no S3 backend and
 * the manifest's path stays as the local filename.
 */
public class S3ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(S3ArtifactStore.class);

    private final S3AsyncClient s3Client;
    private final String bucket;
    private final String envPrefix;

    public S3ArtifactStore(S3AsyncClient s3Client, String bucket, String envPrefix) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.envPrefix = envPrefix == null || envPrefix.isBlank() ? "dev" : envPrefix;
    }

    /** {@code true} when the bucket is configured; otherwise uploads are skipped. */
    public boolean isEnabled() {
        return bucket != null && !bucket.isBlank();
    }

    /**
     * Deterministic S3 URI for the run's manifest. Computed without
     * touching S3 so the adapter can embed it in {@code Audit.manifestPath}
     * before the actual upload runs in {@code PortalRunService}'s finally
     * block. Returns {@code null} when S3 is not configured — callers
     * fall back to the local filename.
     */
    public String urlFor(String portalId, String runId) {
        if (!isEnabled()) return null;
        return "s3://" + bucket + "/" + envPrefix + "/runs/" + portalId + "/" + runId + "/manifest.json";
    }

    /**
     * Uploads every regular file under {@code runDir} (top level only — the
     * run dir has no nested subdirs in production runs) to S3 in parallel.
     * Blocks until all uploads complete.
     *
     * @return the {@code s3://...} URI of {@code manifest.json}, or
     *         {@code null} when S3 is not configured or the upload failed
     *         (failure must never flip a successful run to FAILED).
     */
    public String uploadRunDir(Path runDir, String portalId, String runId) {
        if (!isEnabled()) {
            log.debug("S3 upload skipped — no bucket configured runId={}", runId);
            return null;
        }
        if (!Files.isDirectory(runDir)) {
            log.warn("S3 upload skipped — runDir missing runId={} path={}", runId, runDir);
            return null;
        }

        String keyPrefix = envPrefix + "/runs/" + portalId + "/" + runId + "/";
        List<CompletableFuture<?>> uploads = new ArrayList<>();

        try (Stream<Path> files = Files.list(runDir)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String key = keyPrefix + file.getFileName().toString();
                PutObjectRequest req = PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build();
                uploads.add(s3Client.putObject(req, AsyncRequestBody.fromFile(file)));
            });
        } catch (IOException e) {
            log.warn("S3 upload failed to enumerate runDir runId={} error={}", runId, e.toString());
            return null;
        }

        try {
            CompletableFuture.allOf(uploads.toArray(CompletableFuture[]::new))
                    .get();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("S3 upload failed runId={} uploaded={} error={}",
                    runId, uploads.size(), e.toString());
            return null;
        }

        String manifestUri = "s3://" + bucket + "/" + keyPrefix + "manifest.json";
        log.info("artifacts uploaded count={} manifestUri={}", uploads.size(), manifestUri);
        return manifestUri;
    }
}
