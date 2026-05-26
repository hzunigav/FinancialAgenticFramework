# Run-artifact persistence (S3)

The agent-worker writes per-run artifacts (manifest, screenshot, HAR,
trace, envelopes) into a local directory while a run executes. In
Fargate that directory lives in ephemeral container storage and is
destroyed when the task exits, so production runs upload the entire
directory to S3 the moment the run finishes — success, failure, or
shadow halt.

## Object layout

```
s3://<bucket>/<envPrefix>/runs/<portalId>/<runId>/manifest.json
s3://<bucket>/<envPrefix>/runs/<portalId>/<runId>/report.png
s3://<bucket>/<envPrefix>/runs/<portalId>/<runId>/network.har
s3://<bucket>/<envPrefix>/runs/<portalId>/<runId>/trace.zip
s3://<bucket>/<envPrefix>/runs/<portalId>/<runId>/payroll-capture-result.v1.json   (capture runs)
s3://<bucket>/<envPrefix>/runs/<portalId>/<runId>/payroll-submit-result.v1.json    (submit runs)
```

In production: `<bucket>` = `prod-financeagent-artifacts`, `<envPrefix>` = `prod`.

- `<portalId>` matches the YAML descriptor id (e.g. `ccss-sicere`).
- `<runId>` matches `runtime.runId` from the run's manifest:
  `yyyyMMdd'T'HHmmss-XXXXX` (UTC), e.g. `20260526T140312-3a9b4`.

The S3 URI of `manifest.json` is also surfaced inside the result
envelope as `audit.manifestPath`, so Praxis can derive the prefix
without parsing the URI:

```json
{
  "audit": {
    "manifestPath": "s3://prod-financeagent-artifacts/prod/runs/ccss-sicere/20260526T140312-3a9b4/manifest.json",
    "payloadSha256": "…"
  }
}
```

The other artifacts live in the same prefix — strip `/manifest.json`
and append the filename you want.

## Configuration

Wired through `agent.worker.artifacts-bucket` in `application.yml`,
populated from the `ARTIFACTS_BUCKET` env var. When the property is
blank (local dev), `S3ArtifactStore.isEnabled()` returns false and
upload is skipped silently; `audit.manifestPath` falls back to
`"manifest.json"` (the local filename).

The S3 key prefix uses `agent.worker.queue-prefix` (env var
`SQS_QUEUE_PREFIX`, defaulted by `JAVA_TOOL_OPTIONS` to the
deployment's env prefix — `prod` in production). Same env var as
SQS queues, so a portal's S3 prefix and its queues stay in sync.

## Lifecycle and durability

- Bucket-level lifecycle rule expires every object 90 days after
  upload (`Filter: ""`, applies to all keys). Adjust via
  `aws s3api put-bucket-lifecycle-configuration` if compliance needs
  longer retention.
- No versioning — runs are immutable per-runId; nothing rewrites a
  key.
- Public access fully blocked.
- Failure to upload is non-fatal. The run status is unaffected; the
  worker logs a WARN and the envelope still references the
  deterministic S3 URI (which 404s until the next manual
  backfill, useful as an ops signal).

## Praxis access

### Granting Praxis read access

Praxis BPM runs as a separate service with its own task/execution
role. To let workflow steps fetch a worker run's manifest or
screenshot, attach this statement to the Praxis execution role:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "PraxisReadFinanceAgentArtifacts",
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::prod-financeagent-artifacts",
        "arn:aws:s3:::prod-financeagent-artifacts/*"
      ]
    }
  ]
}
```

`s3:ListBucket` is optional — only needed if Praxis enumerates the
prefix (e.g. listing every file from a run rather than fetching
specific filenames it already knows). For straight GetObject access
to the URIs surfaced in `audit.manifestPath`, `s3:GetObject` alone is
sufficient.

### Fetching artifacts from a workflow run

Once `payroll-capture-result.v1.json` or `payroll-submit-result.v1.json`
arrives on the Praxis results queue, the embedded
`audit.manifestPath` is the S3 URI for that run's manifest. From a
Flowable Service Task (Java delegate), Spring service, or script
task:

**1. Parse the URI.** Split on `s3://` and `/`:

```java
URI uri = URI.create(result.getAudit().getManifestPath());
String bucket = uri.getHost();
String key    = uri.getPath().substring(1);   // strip leading /
```

**2. Fetch with the AWS SDK (v2):**

```java
S3Client s3 = S3Client.create();   // uses the Praxis task role
GetObjectRequest req = GetObjectRequest.builder()
        .bucket(bucket).key(key).build();
try (var stream = s3.getObject(req)) {
    String manifestJson = new String(stream.readAllBytes(), UTF_8);
    // … inspect manifest.steps[], manifest.finalUrl, manifest.error, etc.
}
```

**3. Fetch sibling artifacts** (report.png, network.har, trace.zip)
by replacing the trailing filename in the same prefix:

```java
String prefix = key.substring(0, key.lastIndexOf('/') + 1);
GetObjectRequest screenshot = GetObjectRequest.builder()
        .bucket(bucket).key(prefix + "report.png").build();
```

**4. From a script task** (Groovy/Javascript) you can call the same
SDK via the Flowable Java integration, or invoke an existing Praxis
helper bean (e.g. `s3ArtifactReader.readManifest(audit.getManifestPath())`).

### Workflow patterns

- **Audit-on-failure.** Subscribe a Service Task to FAILED /
  PARTIAL / MISMATCH result statuses. Fetch `manifest.json`, pull
  out `manifest.error` and the last few `manifest.steps[]`, attach
  to the HITL task or escalation notification.
- **Operator HITL screenshot review.** Pre-sign a GetObject URL for
  `report.png` (15-minute TTL) and embed it in the human task form
  so the reviewer can see the portal state at submission time
  without granting them direct S3 access.
- **Forensic replay.** `trace.zip` can be opened with `npx playwright
  show-trace` for step-by-step playback of the entire run, including
  DOM snapshots and network log. Download, hand to engineering.

### Pre-signed URLs

If you need to expose an artifact to a browser-side UI without
brokering bytes through Praxis:

```java
S3Presigner presigner = S3Presigner.create();
GetObjectRequest get = GetObjectRequest.builder()
        .bucket(bucket).key(prefix + "report.png").build();
PresignedGetObjectRequest signed = presigner.presignGetObject(
        GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .getObjectRequest(get).build());
URL url = signed.url();
```

The presigner needs `s3:GetObject` itself (it signs against the
caller's credentials), so the same Praxis role policy above is
sufficient.

## Quick reference

| What | Where |
|---|---|
| Bucket | `prod-financeagent-artifacts` |
| Key prefix | `prod/runs/<portalId>/<runId>/` |
| Manifest URI | embedded in `result.audit.manifestPath` |
| Retention | 90 days (lifecycle rule `expire-90d`) |
| Worker IAM | `s3:PutObject` on `arn:aws:s3:::prod-financeagent-artifacts/*` |
| Praxis IAM | `s3:GetObject` (+ optional `s3:ListBucket`) on the same bucket |
| Local dev | `ARTIFACTS_BUCKET` unset → upload skipped, local-disk only |
