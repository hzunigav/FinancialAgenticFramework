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
    public Upload uploadRunDir(Path runDir, String portalId, String runId) {
        if (!isEnabled()) {
            log.debug("S3 upload skipped — no bucket configured runId={}", runId);
            return Upload.none();
        }
        if (!Files.isDirectory(runDir)) {
            log.warn("S3 upload skipped — runDir missing runId={} path={}", runId, runDir);
            return Upload.none();
        }

        String keyPrefix = envPrefix + "/runs/" + portalId + "/" + runId + "/";
        String folderUri = "s3://" + bucket + "/" + keyPrefix;
        List<CompletableFuture<?>> uploads = new ArrayList<>();
        List<StoredArtifact> stored = new ArrayList<>();

        // Walks the tree rather than listing the top level: adapters that write
        // into a subdirectory (ccss-sicere-reports puts its archived documents
        // in reports/, and fixture capture uses fixtures/) would otherwise have
        // their output dropped silently — isRegularFile skips the directory and
        // nothing reports a problem, so the run still looks clean.
        try (Stream<Path> files = Files.walk(runDir)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                // Relative path keeps the subdirectory in the S3 key; the
                // separator has to be normalised because a worker developing on
                // Windows would otherwise emit backslashes into the key.
                String relative = runDir.relativize(file).toString().replace('\\', '/');
                String key = keyPrefix + relative;
                stored.add(new StoredArtifact(relative, "s3://" + bucket + "/" + key, sizeOf(file)));
                PutObjectRequest req = PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build();
                uploads.add(s3Client.putObject(req, AsyncRequestBody.fromFile(file)));
            });
        } catch (IOException e) {
            log.warn("S3 upload failed to enumerate runDir runId={} error={}", runId, e.toString());
            return Upload.none();
        }

        try {
            CompletableFuture.allOf(uploads.toArray(CompletableFuture[]::new))
                    .get();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("S3 upload failed runId={} uploaded={} error={}",
                    runId, uploads.size(), e.toString());
            return Upload.none();
        }

        String manifestUri = "s3://" + bucket + "/" + keyPrefix + "manifest.json";
        log.info("artifacts uploaded count={} manifestUri={}", uploads.size(), manifestUri);
        return new Upload(manifestUri, folderUri, List.copyOf(stored));
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * What an upload put in S3.
     *
     * <p>Consumers need more than the manifest URI: Praxis is handed the folder
     * and the per-file locations so it can fetch a specific document without
     * having to know how the run directory is laid out.
     *
     * @param manifestUri {@code s3://…/manifest.json}, or null when S3 is not
     *                    configured or the upload failed — callers treat a null
     *                    here exactly as before, artifacts stay on local disk
     * @param folderUri   {@code s3://…/<runId>/}, trailing slash included
     * @param files       every object written, relative name and absolute URI
     */
    public record Upload(String manifestUri, String folderUri, List<StoredArtifact> files) {

        /** No upload happened; preserves the historical null manifest URI. */
        public static Upload none() {
            return new Upload(null, null, List.of());
        }
    }

    /**
     * @param name relative path inside the run directory, e.g.
     *             {@code reports/PlanillaNeoproc082026.pdf}
     * @param uri  fully-qualified {@code s3://} location
     * @param bytes object size, or -1 if it could not be read
     */
    public record StoredArtifact(String name, String uri, long bytes) {}
}
