# Praxis Integration Handoff — Phase 1

Everything the Praxis team needs to build, configure, or decide so the agent-worker can run under Praxis's BPM orchestration in production. **This document is the scope of Phase 1.** Phase 2 (per-portal onboarding for CCSS / INS / Hacienda) and Phase 3 (deploy packaging) are referenced but not expanded here — they're downstream of this work.

**Companion docs (read in this order):**
1. [contract-api/CONTRACT.md](contract-api/CONTRACT.md) — wire format, envelope schemas, and the per-firm KMS encryption runbook.
2. [PayrollOrchestrationFlow.md](PayrollOrchestrationFlow.md) — the BPMN process model that wraps the envelopes described in CONTRACT.md.
3. This doc — the plumbing between Praxis and the worker that those two documents assume exists.

---

## 0. Context for the Praxis team

### What this replaces

The Praxis roadmap previously listed an **Agentic AI Microservice** feature — a Python/FastAPI service for headless browser scraping, LLM-powered extraction, and Vault credential management. **That feature is replaced by the agent-worker described in this document** and should be removed from the Praxis backlog (or redefined as "agent-worker runtime — external service, integrated via envelope contract").

Specifically:
- **Headless browser scraping** is handled by [agent-worker](agent-worker/), a Java service using Playwright with declarative YAML portal descriptors. One descriptor per portal, versioned in git, auditable step-by-step.
- **Credential management** uses **AWS KMS** (per-firm envelope-encryption keys, replacing the earlier Vault `transit` plan) and **AWS Secrets Manager** (portal credentials, replacing the earlier Vault `kv-v2` plan), both authenticated via IAM. Covered by Workstreams A and B, with a single source of truth (see §3.0 below). Decision rationale: see [InfraSetup.md Track B](InfraSetup.md).
- **LLM-powered extraction is not built and not needed for the current scope.** The Costa Rica government portals (CCSS Sicere, INS RT-Virtual, Hacienda OVI) and AutoPlanilla all have structured DOMs that declarative CSS/role selectors hit with 100% accuracy at zero token cost. Adding an LLM extraction layer here would be strictly worse: slower, non-deterministic, harder to audit for a financial submission, and more expensive per run. If a future use case ever requires extraction from an unstructured source (e.g., a PDF emailed back from Hacienda), we add a focused extractor as a new adapter type — the envelope contract already accommodates it, no architectural change needed.

### Why Java, not Python/FastAPI

The agent-worker is a Spring-less Java service using Playwright's Java client. Reasons:
- **Matches Praxis's existing stack.** Praxis is already Spring Boot + Flowable + Java; a Java worker is zero operational surface area beyond what Praxis already runs.
- **Playwright Java is mature and strictly better for this use case than Python's Selenium/Playwright combos** for the Material UI / React-heavy portals we target (Emotion hash class churn, network-idle waits, trace artifacts).
- **No Python runtime to ship or patch** in the deployment pipeline. One JVM image per worker, reused across all portal deployments.

If Praxis ops has a strong preference for Python services specifically, please raise it — the contract would survive a rewrite, but there's no technical reason to take that cost on.

### Where this leaves the Praxis backlog

The line item "Agentic AI Microservice — Python/FastAPI service for headless browser scraping, LLM-powered extraction, Vault credential management" can be closed. Its scope is absorbed by (a) the agent-worker as an external service, and (b) the integration work enumerated below.

---

## 1. Division of responsibility

What each side owns in the production flow:

| Concern | Praxis owns | Agent-worker owns |
|---|---|---|
| BPMN process definition + deployment | ✓ | |
| Timer triggers, User Tasks, gateways | ✓ | |
| Envelope construction (populating `envelope.*` + `task.*`) | ✓ | |
| Encryption key management (AWS KMS + per-firm keys) | ✓ | |
| Portal credential storage (AWS Secrets Manager) | ✓ | |
| RabbitMQ broker + exchange/queue topology | ✓ | |
| Worker deployment (container orchestration, autoscaling) | ✓ | |
| Portal descriptors (`*.yaml`) + adapters | | ✓ |
| Playwright-driven portal interaction | | ✓ |
| `payroll-*.v1` envelope wire format | shared (contract-api jar) | shared |
| Audit bundles (manifest.json, HAR, screenshot) | | ✓ |
| Idempotency enforcement (duplicate envelopeId detection) | | ✓ |

Two one-way dependencies flow between them:
- **Praxis → worker:** a `PayrollSubmitRequest` envelope on a RabbitMQ queue.
- **Worker → Praxis:** a `PayrollSubmitResult` envelope on a RabbitMQ queue, correlated back via `envelope.businessKey`.

Everything else is internal to one side.

---

## 2. Workstream A — AWS KMS envelope encryption

**Goal:** enable field-level encryption/decryption of envelope bodies using per-firm AWS KMS keys.

**Reference:** [CONTRACT.md §4 "Production (Praxis-side runbook)"](contract-api/CONTRACT.md#4-encryption) has the full spec. Summary:

### A.1 Add the AWS SDK v2 KMS dependency

- [ ] Add to Praxis's `pom.xml`:
  ```xml
  <dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>kms</artifactId>
  </dependency>
  ```
- [ ] Credentials resolve via the AWS SDK's default provider chain. In production this is Praxis's service IAM role (instance profile, IRSA on EKS, or ECS task role) — no static keys in config. The role needs the policy from [InfraSetup.md §B.3](InfraSetup.md): `kms:CreateKey`, `CreateAlias`, `ListAliases`, `EnableKeyRotation`, `Encrypt`, `GenerateDataKey`, `Decrypt` (the last three scoped to keys tagged with any `firmId`).

### A.2 Provision a per-firm KMS key at firm-onboarding time

- [ ] Hook into the existing firm-onboarding flow. Suggested integration: a `FirmOnboardingListener` Spring bean that consumes `FirmCreatedEvent` and runs:
  ```java
  if (kms.listAliases().aliases().stream().noneMatch(a ->
          a.aliasName().equals("alias/payroll-firm-" + firmId))) {
      CreateKeyResponse key = kms.createKey(CreateKeyRequest.builder()
          .description("Payroll envelope key — firm " + firmId)
          .keyUsage(KeyUsageType.ENCRYPT_DECRYPT)
          .keySpec(KeySpec.SYMMETRIC_DEFAULT)
          .tags(Tag.builder().tagKey("firmId").tagValue(String.valueOf(firmId)).build())
          .build());
      kms.createAlias(CreateAliasRequest.builder()
          .aliasName("alias/payroll-firm-" + firmId)
          .targetKeyId(key.keyMetadata().keyId())
          .build());
      kms.enableKeyRotation(EnableKeyRotationRequest.builder()
          .keyId(key.keyMetadata().keyId())
          .build());
  }
  ```
- [ ] **Idempotency note:** unlike Vault `transit/keys`, KMS `CreateAlias` is *not* idempotent — repeated calls throw `AlreadyExistsException`. The `listAliases` check above makes the listener safe to re-run.
- [ ] **Acceptance:** after `FirmCreatedEvent` for firmId=99999, `aws kms describe-key --key-id alias/payroll-firm-99999` returns metadata (not `NotFoundException`).

### A.3 Implement `KmsEnvelopeClient` in Praxis

- [ ] Spring bean wrapping AWS SDK v2 `KmsClient`. Two methods:
  ```java
  String encrypt(long firmId, byte[] plaintext);   // returns "aws-kms:v1:<base64(JSON)>"
  byte[] decrypt(long firmId, String ciphertext);  // accepts "aws-kms:v1:<base64(JSON)>"
  ```
- [ ] Implementation per [CONTRACT.md §4](contract-api/CONTRACT.md#4-encryption):
  - Encrypt: `GenerateDataKey(alias/payroll-firm-<firmId>, AES_256)` → AES-256-GCM the body with the plaintext DEK + random 96-bit IV → serialize `{kid, edk, iv, ct}` to JSON → base64-wrap with `aws-kms:v1:` prefix.
  - Decrypt: strip prefix → base64 decode → JSON parse → `Decrypt(edk)` → AES-256-GCM decrypt.
- [ ] **Acceptance:** given a capture envelope produced by the worker in staging (`encryption.scheme=aws-kms-envelope-v1`, KMS alias provisioned for the test firm), Praxis's `KmsEnvelopeClient.decrypt()` returns the same cleartext body the worker's encrypt call started with.

### A.4 Switch the worker to `aws-kms-envelope-v1`

- [ ] Once A.1–A.3 are green in staging, the worker is flipped via env var: `FINANCEAGENT_CIPHER=kms`. The new `KmsEnvelopeCipher` class (~50 lines around AWS SDK v2, to be added to `common-lib`) implements the existing `EnvelopeCipher` interface. Small change, agent-worker side, after Praxis confirms A.3.

---

## 3. Workstream B — AWS Secrets Manager for portal credentials

**Goal:** eliminate the dev-only `~/.financeagent/secrets.properties` file. Portal credentials live in AWS Secrets Manager, with the right scope for each portal's real-world access model.

**Today (dev):** `LocalFileCredentialsProvider` reads `portals.<portalId>.credentials.<name>=<value>` from a flat properties file. Agent looks up credentials by portalId only — no firm scoping, no concept of shared vs per-firm creds.

**Production:** credentials scope depends on the portal. Two models exist in the wild:

| Scope | Example portals | Real-world meaning |
|---|---|---|
| **per-firm** | CCSS Sicere | Each client firm has its own patrono-level login. NeoProc logs in *as the firm*; a different firm = different credentials. |
| **shared** | INS RT-Virtual, Hacienda OVI, AutoPlanilla | A single NeoProc-owned login covers every client firm. After login, the agent picks the target client on the portal page using a firm-specific identifier (cédula jurídica / client code). |

### B.0 Centralization — one source of truth

A legitimate concern when two services need credentials is where they're entered and who owns them. The design here is deliberately centralized:

- **One UI for entry.** The Praxis firm-admin UI is the *only* place a human ever enters portal credentials. Agent-worker has no UI. Admins never touch a worker container or a secrets file.
- **One store.** All credentials (portal creds for the agent-worker, plus whatever Praxis uses for its own external integrations) live in AWS Secrets Manager. Path-prefix isolation (`financeagent/...` for this work) keeps Praxis's existing secrets cleanly separated.
- **Two readers, narrowly scoped.** Praxis reads and writes via its own service IAM role (with `secretsmanager:CreateSecret`/`PutSecretValue`/`GetSecretValue` on `financeagent/*`). The agent-worker reads via its own ECS task IAM role with `secretsmanager:GetSecretValue` *only* on `financeagent/firms/*/portals/*` and `financeagent/shared/portals/*`. Neither service can write what the other writes, or read outside its scope.
- **One admin flow per portal, not per firm-portal combination.** For shared-creds portals, NeoProc enters credentials *once* (at tenant level), not N times. Rotation touches one path. The per-firm metadata (which client to pick after login) is captured separately and is *not* a secret.

Why not push credentials into the envelope itself and skip the worker's Secrets Manager integration entirely? We considered it. Feasible (credentials get encrypted with the per-firm KMS key and ride the envelope), but the tradeoffs land wrong: credentials end up in flight on the message bus on every run, Praxis becomes the critical path for every worker action, and the blast radius on a broker compromise is larger. A read-only IAM task role on the worker side is zero new code (just policy) — strictly cleaner.

What the user-facing experience looks like:

```
   [Payroll admin]
        │
        │  per-firm portals (CCSS):  enters credentials per firm
        │  shared portals (INS/Hacienda/AutoPlanilla):
        │       — enters tenant credentials ONCE
        │       — per-firm: enters the client identifier for that portal
        ▼
   ┌─────────────────────┐
   │  Praxis (Spring)    │──── writes ───┐
   └─────────────────────┘                │
                                          ▼
                                  ┌─────────────────┐
                                  │ Secrets Manager │
                                  └─────────────────┘
                                          ▲
                                          │
   ┌─────────────────────┐                │
   │   Agent-worker      │──── reads ─────┘
   │   (scope chosen per │
   │    descriptor)      │
   └─────────────────────┘
```

So: one place to enter, one place to rotate, one audit trail (CloudTrail). No duplication.

### B.1 Secrets Manager layout

- [ ] No upfront mount step — Secrets Manager has no equivalent of Vault's mount. Secrets are created on demand at the path `financeagent/...`.
- [ ] **Two subtrees, one per credential scope:**
  ```
  # Per-firm — one secret per (firmId, portalId). Used by: CCSS Sicere.
  financeagent/firms/<firmId>/portals/<portalId>
    -> { "username": "...", "password": "...", "mfaMethod": "totp|sms|none", "mfaSecret": "..." }

  # Shared — one secret per portalId, reused across every firm in this
  # Praxis tenant. Used by: INS RT-Virtual, Hacienda OVI, AutoPlanilla.
  financeagent/shared/portals/<portalId>
    -> { "username": "...", "password": "...", "mfaMethod": "totp|sms|none", "mfaSecret": "..." }
  ```
- [ ] **Acceptance:** `aws secretsmanager get-secret-value --secret-id financeagent/firms/12345/portals/ccss-sicere` returns firm 12345's CCSS login; `aws secretsmanager get-secret-value --secret-id financeagent/shared/portals/ins-rt-virtual` returns NeoProc's shared INS login.

### B.2 Firm onboarding UI

Two workflows, depending on portal scope:

- [ ] **Per-firm portals.** Existing pattern: when a firm is onboarded (or when payroll agents are enabled for an already-onboarded firm), the admin enters credentials per firm per portal. UI calls `secretsmanager:CreateSecret` (first time) or `PutSecretValue` (rotation) on `financeagent/firms/<firmId>/portals/<portalId>`.
- [ ] **Shared portals (tenant-level credentials).** NeoProc enters credentials once at tenant setup time (not per firm). UI writes to `financeagent/shared/portals/<portalId>`. The credentials form is only shown to users with tenant-admin role.
- [ ] **Shared portals (per-firm client identifier).** Separately, each firm's record gets a **client identifier** field per shared portal — what the portal indexes that firm by (cédula jurídica, internal client code, etc.). Stored in Praxis as a `FirmPortalIdentifier` entity, **not** in Secrets Manager. **Not a secret.**
- [ ] **Envelope carries the client identifier for shared-creds runs.** Extend the submit envelope's `task` block with an optional `clientIdentifier` field. Praxis populates it from the firm's record when dispatching to a shared-creds portal. The worker feeds it into the descriptor as `${params.clientIdentifier}`, and the descriptor's `steps` block uses it to pick the right client on the post-login page:
  ```yaml
  steps:
    - action: select               # or click / fill, depending on portal UI
      selector: 'role=combobox[name="Cliente"]'
      value: "${params.clientIdentifier}"
    - action: ...                  # then the normal per-firm flow
  ```
  See also the `credentialScope: shared` declaration in the portal descriptor ([autoplanilla.yaml](agent-worker/src/main/resources/portals/autoplanilla.yaml) is a live example) — the worker uses this to know which subtree to read.
- [ ] **MFA handling:** out of scope for Phase 1 if none of the three target portals require it. If any do — CCSS likely does — see [PortalOnboarding.md](PortalOnboarding.md) for how the worker's `pause` action lets an operator enter MFA codes interactively. Revisit once the Phase 2 team reports back.

### B.3 Credentials provider swap on the worker

- [ ] Agent-worker side: add an `AwsSecretsManagerCredentialsProvider` in `common-lib` that implements the existing `CredentialsProvider` interface. On lookup, it reads the target portal's descriptor, checks `credentialScope`, and fetches from either `financeagent/firms/<firmId>/portals/<portalId>` (per-firm) or `financeagent/shared/portals/<portalId>` (shared). The descriptor-driven scope avoids any runtime guessing.
- [ ] Worker picks the provider via env var:
  ```
  FINANCEAGENT_CREDENTIALS=local    (default — LocalFileCredentialsProvider)
  FINANCEAGENT_CREDENTIALS=aws      (AwsSecretsManagerCredentialsProvider)
  ```
- [ ] Worker authenticates to AWS via its **ECS task role** — no AppRole, no static keys, no token lifecycle. The task role's IAM policy ([InfraSetup.md §B.3](InfraSetup.md)) grants read-only on both subtrees and `kms:Decrypt` on per-firm KMS keys, and nothing else. The descriptor's `credentialScope` constrains which path is actually hit per run.

### B.4 Scoping enforcement

- [ ] The worker uses the `envelope.firmId` from the incoming `PayrollSubmitRequest` as the authoritative firm scope. It must not accept a firmId from any other source (system property, env, etc.).
- [ ] For shared-creds portals, the worker uses `task.clientIdentifier` from the envelope as the authoritative per-firm selector on the portal page. The worker does not derive the identifier from the firmId — Praxis is the source of truth for which portal-side identifier maps to which firm.
- [ ] **Acceptance (per-firm):** a worker receiving an envelope with `firmId=12345` targeting a per-firm portal cannot read credentials under `financeagent/firms/67890/...` even if the envelope body claims otherwise. The worker resolves the path from `envelope.firmId`, never from external input.
- [ ] **Acceptance (shared):** a worker receiving an envelope with `firmId=12345` and `task.clientIdentifier=3-101-000001` targeting a shared portal logs in with the shared creds, selects the `3-101-000001` client on the post-login page, and submits. The audit trail records `firmId=12345, portal=ins-rt-virtual, clientIdentifier=3-101-000001` even though the underlying session is shared.

---

## 4. Workstream C — RabbitMQ + worker runtime

**Goal:** replace the CLI entry point (`mvn exec:java -Dportal.id=... -Dparams.firmId=...`) with a queue-driven worker runtime.

### C.1 Broker + topology

- [ ] Stand up (or reuse) a RabbitMQ broker reachable from both Praxis and the worker cluster.
- [ ] Declare exchanges + queues. Recommended topology:
  ```
  Exchange: financeagent.tasks             (direct)
  Exchange: financeagent.results           (direct)
  Exchange: financeagent.dlx               (fanout, dead-letter)

  Queues:
    financeagent.tasks.capture             (routing-key: capture)
    financeagent.tasks.submit.ccss-sicere  (routing-key: submit.ccss-sicere)
    financeagent.tasks.submit.ins-rt-virtual
    financeagent.tasks.submit.hacienda-ovi
    financeagent.tasks.submit.mock-payroll (dev/staging only)

    financeagent.results                   (shared results queue, Praxis consumes)

    financeagent.dlq                       (bound to the fanout dlx)
  ```
- [ ] Per-queue config:
  - Durable: true.
  - `x-dead-letter-exchange: financeagent.dlx`.
  - `x-message-ttl: 3600000` (1 hour — a payroll submission that hasn't been picked up in an hour is stale).
- [ ] **Acceptance:** Praxis can publish a test message to `financeagent.tasks.submit.mock-payroll` and observe it arrive on that queue via the RabbitMQ management UI.

### C.2 Message envelope

- [ ] Message body: the cleartext `PayrollSubmitRequest` JSON (body of the envelope is encrypted per §2, so the message is safe to log at broker level — no PII leaks).
- [ ] Message properties:
  - `content-type: application/json`
  - `message-id: <envelope.envelopeId>`  (used by broker for deduplication if `x-message-deduplication` is on)
  - `correlation-id: <envelope.businessKey>`  (Praxis uses this to correlate the response back to the originating process instance)
  - `reply-to: financeagent.results`
  - `type: <schema>`  (e.g., `payroll-submit-request.v1`)

### C.3 Worker runtime change

This is the biggest code delta on the agent-worker side — replacing `Agent.main()` (CLI) with a Spring Boot consumer app. Tracked on the worker side, but Praxis needs to agree on the semantics:

- [ ] Worker subscribes to the relevant `financeagent.tasks.submit.<portalId>` queue based on its deployed portal descriptor. One worker instance = one portal. (Concurrency is per-portal, not shared — CCSS's rate limit differs from AutoPlanilla's.)
- [ ] On message receipt:
  1. Deserialize to `PayrollSubmitRequest`.
  2. Validate `envelope.envelopeId` is not in the already-processed set (idempotency).
  3. Fetch credentials from Secrets Manager scoped to `envelope.firmId` + portal descriptor's id (per [§B.3](#b3-credentials-provider-swap-on-the-worker)).
  4. Run the descriptor + adapter pair (same code as today; the only change is the entry point).
  5. Publish `PayrollSubmitResult` to `financeagent.results` with `correlation-id` set to the incoming envelope's `businessKey`.
  6. Ack the message on success; nack-requeue=false on unrecoverable errors (message flows to DLQ).
- [ ] **Acceptance:** Praxis publishes a `payroll-submit-request.v1` for mock-payroll, the worker processes it, and Praxis observes a `payroll-submit-result.v1` on `financeagent.results` with matching `correlation-id`.

### C.4 Idempotency + retry policy

- [ ] Worker stores processed `envelopeId` values in a shared state (Redis or a dedicated Postgres table — TBD which exists in Praxis's infra; default to Postgres if unsure). TTL: 7 days.
- [ ] Duplicate receipt → return the previously-computed result (if still in artifact storage) or emit a `FAILED` envelope with `errorDetail.category=DUPLICATE_ENVELOPE`. Do **not** re-execute portal actions for a duplicate — the portal is not idempotent.
- [ ] Worker-side retries: zero for write operations (portal submissions are side-effectful). Retries are a Praxis concern — if a message hits the DLQ, Praxis's retry policy decides whether to re-publish a new envelope (with a new envelopeId) or escalate to a human.

### C.5 Per-portal rate limiting

Government portals will throttle us aggressively once we submit at scale across firms. Failures surface as mysterious timeouts that look like data issues — much cheaper to declare and enforce limits defensively from day one than diagnose the same throttle event three times.

- [ ] **Descriptor declares the limit.** Each portal descriptor gains a `rateLimit: { maxConcurrent, minIntervalSeconds }` block (new field, agent-worker side — tracked in [EnhancementsBacklog.md](EnhancementsBacklog.md)). Example values to use as starting points until real numbers come from the portals:
  ```yaml
  # ccss-sicere.yaml (tight limits likely)
  rateLimit:
    maxConcurrent: 1
    minIntervalSeconds: 5

  # ins-rt-virtual.yaml, hacienda-ovi.yaml
  rateLimit:
    maxConcurrent: 2
    minIntervalSeconds: 2
  ```
- [ ] **Worker enforces the limit in-process.** A portal-keyed semaphore gates concurrent runs; a rate-limiter enforces the minimum interval between submissions. Limits are per-worker-process; across multiple worker replicas, see the next bullet.
- [ ] **Praxis honors the limit at the broker level.** RabbitMQ consumer prefetch on each `financeagent.tasks.submit.<portalId>` queue must not exceed `maxConcurrent * replicas`. If CCSS is declared as `maxConcurrent: 1` and runs with 2 worker replicas, set the queue prefetch to 2. This keeps the broker from handing out messages the workers cannot immediately process.
- [ ] **Acceptance:** submitting 10 `mock-payroll` envelopes to the queue simultaneously while the descriptor declares `maxConcurrent: 2` results in at most 2 active browser sessions against the mock harness at any instant, and all 10 eventually complete in order.

---

## 5. Workstream D — BPMN Service Task + Receive Task wiring

**Goal:** connect the process definition in [PayrollOrchestrationFlow.md](PayrollOrchestrationFlow.md) to the RabbitMQ topology from §4.

### D.1 Service Task pattern

- [ ] Each "Submit to X" box in the top-level flow maps to a Flowable `ServiceTask` with a `JavaDelegate` that:
  1. Builds a `PayrollSubmitRequest` envelope from the captured payroll + the target portal identifier (see [CONTRACT.md §6 "Building a submit-request from a capture result"](contract-api/CONTRACT.md#6-consuming-this-contract-from-praxis)).
  2. Encrypts the body via `KmsEnvelopeClient.encrypt(firmId, ...)`.
  3. Publishes to `financeagent.tasks.submit.<targetPortal>` with the properties from §C.2.
  4. **Does not block.** This is a fire-and-forget Service Task; the response comes back via a Receive Task.

### D.2 Receive Task pattern

- [ ] After each Service Task, a `ReceiveTask` with correlation key = `envelope.businessKey` waits for the corresponding `PayrollSubmitResult`.
- [ ] A Praxis-side RabbitMQ listener on `financeagent.results` matches each incoming result to its Receive Task by `correlation-id` and signals the process.
- [ ] Timeout handling: a boundary timer event on the Receive Task fires after (say) 10 minutes → routes to the `FAILED` / engineering-escalation user task per [PayrollOrchestrationFlow §4](PayrollOrchestrationFlow.md#4-status-vocabulary--routing).

### D.3 Aggregate service task (Parallel Gateway join)

- [ ] After the three parallel Receive Tasks join, the `Aggregate roster_diff` Service Task is a plain in-process Flowable delegate — no RabbitMQ round-trip. It just merges the three `PayrollSubmitResult` envelopes into one aggregate decision payload. See [PayrollOrchestrationFlow §2 walkthrough step 6](PayrollOrchestrationFlow.md#walkthrough).

---

## 6. Workstream E — Observability contract

### E.1 Correlation IDs

- [ ] Every envelope carries `envelope.envelopeId`, `envelope.businessKey`, and `envelope.issuerRunId`. The agent-worker logs all three at INFO on every externally-visible action (portal click, submit, scrape).
- [ ] Praxis logs the same three on every Service Task / Receive Task activation. Log format convention (both sides):
  ```
  ts=... level=INFO firmId=... businessKey=... envelopeId=... issuerRunId=... msg="..."
  ```
- [ ] **Acceptance:** given any production envelope, you can `grep` one `envelopeId` in both Praxis logs and worker logs and reconstruct the full cross-system timeline.

### E.2 Tracing

- [ ] OpenTelemetry spans: Praxis's Service Task is the root span, worker processing is a child span, portal interaction steps are grandchild spans.
- [ ] Trace id propagates via RabbitMQ message property `x-trace-id`.
- [ ] Optional for Phase 1 if Praxis doesn't have OTel wired today. Mark as a follow-up but don't block on it.

### E.3 Metrics

- [ ] Agent-worker exposes Prometheus metrics: `agent_capture_duration_seconds`, `agent_submit_duration_seconds`, `agent_reconciliation_gap_colones` (histogram), `agent_roster_diff_size` (counter by bucket: `missingFromPortal` / `missingFromPayroll`).
- [ ] Praxis consumes these for an operations dashboard. Designed alongside the HITL review UI.

---

## 7. Workstream F — Packaging + deployment

### F.1 Container image

- [ ] Multi-stage Dockerfile for the worker:
  - Stage 1: build the fat JAR with `mvn package`.
  - Stage 2: Playwright's official Java image (`mcr.microsoft.com/playwright/java:v1.48.0-jammy` or the current stable) with browsers pre-installed, copy JAR in, run.
- [ ] Image size will be ~1 GB (Chromium + Firefox + Webkit is most of it). That's fine.
- [ ] **Acceptance:** `docker run financeagent-worker:<tag>` on a fresh machine successfully processes a test envelope from a local RabbitMQ.

### F.2 Orchestration

- [ ] Kubernetes Deployment per portal: `financeagent-worker-ccss`, `-ins`, `-hacienda`, `-autoplanilla`. Each with `replicas: 1` initially. (One worker per portal keeps rate-limit enforcement trivial — the portal sees exactly one concurrent session per firm.)
- [ ] Each deployment's config:
  - Env var `PORTAL_ID` = the portal descriptor id (`ccss-sicere` etc).
  - ECS task IAM role with `kms:Decrypt` + `secretsmanager:GetSecretValue` per [InfraSetup.md §B.3](InfraSetup.md).
  - RabbitMQ connection string (from Secrets Manager via task definition `secrets:` block).
  - `FINANCEAGENT_CIPHER=kms`, `FINANCEAGENT_CREDENTIALS=aws`.
- [ ] Resource requests: `memory: 2Gi` (Chromium is memory-hungry), `cpu: 1`. Tune after observing real runs.

### F.3 Artifact retention

- [ ] The `artifacts/` directory produced per run (manifest, HAR, screenshot, tracing zip) must persist for the audit window — 1 year minimum per Costa Rica financial-records law, check with legal.
- [ ] Suggest shipping each completed run's artifacts to S3-compatible object storage (MinIO, AWS S3, whatever Praxis uses for file storage today). Agent-worker adds an S3-upload step after it writes the submit-result envelope; the envelope's `audit.manifestPath` becomes an S3 URL instead of a local path.

---

## 8. Workstream G — Human-in-the-loop (HITL) review

**Goal:** give Praxis a structured, generic way to surface `MISMATCH` and `PARTIAL` results to a reviewer without each portal inventing its own UI or review API.

`MISMATCH` and `PARTIAL` envelopes will accumulate from cycle one — any real drift between AutoPlanilla and the three target portals produces one. Without a contract for what the reviewer sees and can do, the agent emits free-text console messages (as today) and Praxis builds a bespoke UI per portal. Under this workstream, the envelope itself carries the review payload and the action vocabulary is standardized.

### G.1 Envelope gains a `review` block

- [ ] Extend `SubmitResultBody` with an optional `review` block populated only for `MISMATCH` / `PARTIAL` runs. Proposed shape:
  ```json
  "review": {
    "severity": "MISMATCH | PARTIAL",
    "summary":  "human-readable one-liner, e.g. \"Portal grand total exceeds canonical by 1,319,818.62 CRC\"",
    "signals": [
      { "type": "TOTAL_GAP",            "canonical": "17239260.72", "portal": "18559079.34", "gap": "1319818.62" },
      { "type": "MISSING_FROM_PORTAL",  "id": "117040400", "name": "ANDRES MARIN",  "expectedSalary": "902403.59" },
      { "type": "MISSING_FROM_PAYROLL", "id": "999999901", "name": "JUAN PEREZ GHOST", "lastKnownSalary": "1234567.89" }
    ],
    "allowedActions": ["RESUBMIT", "ACKNOWLEDGE", "ESCALATE"]
  }
  ```
- [ ] Agent-worker populates `signals` from the same data it already computes for the roster diff + reconciliation. No new browser interactions; pure restructuring of what the adapter already has in hand.
- [ ] Schema + DTO changes live in `contract-api/`; tracked as a backlog entry ([EnhancementsBacklog.md: Structured HITL review payload](EnhancementsBacklog.md)).

### G.2 Praxis-side review surface

- [ ] A Flowable user task (already in the BPMN design per [PayrollOrchestrationFlow.md §4](PayrollOrchestrationFlow.md)) receives the `review` block as its task-form input.
- [ ] Review UI renders `summary` at the top, lists `signals` grouped by `type` (gap cards, missing-from-portal list, missing-from-payroll list), and exposes exactly the buttons in `allowedActions`. Generic across portals — no per-portal customization needed.
- [ ] Reviewer's choice routes the process:
  - `RESUBMIT` → process loops back to the relevant submit Service Task with a new envelope (new envelopeId; links to the prior via `task.sourceCaptureEnvelopeId`).
  - `ACKNOWLEDGE` → process completes with status `PARTIAL_ACCEPTED`, cycle moves on. Manifest of the run + the reviewer's comment persist in the artifact store.
  - `ESCALATE` → process moves to an engineering escalation user task outside the payroll flow.

### G.3 Observability linkage

- [ ] Each review decision emits a metric (`agent_review_decisions_total{action="resubmit|acknowledge|escalate"}`) so a spike in acknowledgements vs resubmits becomes a visible signal of process drift.
- [ ] The `review.summary` field is what shows on the operations dashboard next to each PARTIAL/MISMATCH envelope. Keep it one line — the full detail is in `signals`.

### G.4 Acceptance

- [ ] A deliberately drifted mock-payroll run (run via [demo/test-roster-drift.sh](demo/test-roster-drift.sh) in dev) produces a `PayrollSubmitResult` envelope with a populated `review` block. Praxis's review UI renders it correctly, and each of the three action buttons routes the BPMN process as specified.

---

## 9. Workstream H — Supervision

**Goal:** catch the classes of failure that per-run observability alone cannot — specifically, work that was supposed to happen and didn't. Flowable guarantees every *started* process instance reaches a terminal state; this workstream guarantees every *expected* process instance gets started, and that the operator has a single place to see issues accumulate.

Adds a supervision surface sitting above the BPMN orchestration from §5 and the observability contract from §6. Praxis-side scope unless otherwise noted; agent-worker dependencies are enumerated in §H.5 and tracked in [ImplementationPlan.md § P1](ImplementationPlan.md).

### H.1 Cycle-coverage reconciler

- [ ] Scheduled Flowable timer job (daily, or 24h after each cycle's expected start) that asserts: *for every firm with `payroll-agents-enabled=true`, a `payroll-cycle` process instance exists for the current period.* Firms with no instance → emit a high-severity alert and create a tenant-admin user task.
- [ ] Catches: timer-trigger failures, onboarding-flag misconfiguration, missing per-firm KMS alias, missing `FirmPortalIdentifier` for shared-creds portals, firms silently dropped during enablement.
- [ ] **Acceptance:** disable the `payroll-agents-enabled` flag on a test firm after the cycle timer fires. The reconciler flags it within 24h, creates the tenant-admin user task, and the dashboard (H.2) surfaces it under a "Missing" view.
- [ ] Size: ~1 eng-day.

### H.2 Supervisor dashboard

- [ ] New React screen in the Praxis app reading from Flowable process state + RabbitMQ queue metrics + the results exchange.
- [ ] Views:
  - **In-flight:** every envelope not in a terminal state, grouped by firm / portal, with age and current BPMN activity.
  - **Recent:** last N cycles of `PayrollSubmitResult`, filterable by status (`SUCCESS` / `PARTIAL` / `MISMATCH` / `FAILED`) and firm.
  - **HITL inbox:** pending user tasks with `review` payload (from §8), sortable by SLA-to-acknowledgment.
  - **System health:** DLQ depth per queue, worker replica status, RabbitMQ consumer lag, cycle-coverage reconciler results.
- [ ] Tenant-admin scope sees all firms; per-firm admins see only their firm.
- [ ] Depends on §8 (`review` payload shape) for the HITL inbox view.
- [ ] Size: ~3 eng-days.

### H.3 Alerting

Three tiers over the Prometheus metrics from §6.3 plus the reconciler output:

| Tier | Signal | Action |
|---|---|---|
| **P0 (page)** | DLQ depth > 0; KMS or Secrets Manager auth failure; RabbitMQ consumer lag > threshold; H.1 reconciler flags ≥ 1 firm; H.4 canary fails | on-call page |
| **P1 (ticket)** | `FAILED` envelope; portal 5xx burst (≥ 3 in 10 min); Read-Back `MISMATCH` rate above rolling baseline | ops queue |
| **P2 (dashboard only)** | `PARTIAL` result (already routes to HITL user task); rate-limit saturation | dashboard only |

- [ ] Recommend CloudWatch alarms over the Prometheus metrics — stack-native on AWS, ~$0.10/alarm/month, no new ops surface. Grafana Cloud free tier is equivalent if already in use. Self-hosted Prometheus + Alertmanager is deferred until multi-product scale.
- [ ] Size: ~1 eng-day.

### H.4 Synthetic canary

- [ ] Praxis-side scheduler publishes a `mock-payroll` envelope every 30 minutes. Result must arrive on `financeagent.results` within SLA or P0 fires.
- [ ] Exercises: RabbitMQ path, KMS encrypt/decrypt, Secrets Manager read, worker liveness, BPMN Receive Task signaling — in the same code paths as production traffic.
- [ ] Agent-worker dependency: keep the `mock-payroll` portal descriptor in the production image and its queue consumable (not gated behind a dev-only flag). See §H.5.
- [ ] Size: ~half eng-day.

### H.5 Agent-worker dependencies

What the worker must expose so Praxis can build H.1–H.4. Tracked on the worker side under [ImplementationPlan.md § P1 "Supervision hooks"](ImplementationPlan.md):

- Prometheus metrics per §6.3 exposed on `/actuator/prometheus`.
- Correlation-ID (envelopeId / businessKey / issuerRunId) on every log line via MDC.
- Structured terminal-state log entry per run: one INFO line with final status + the correlation triple, for log-based alerting without metrics scraping.
- `mock-payroll` descriptor shippable in the production image, queue consumable in prod.

No new Praxis-facing contract. No envelope-schema changes. Purely ops-surface additions on the worker side.

### H.6 Acceptance

- [ ] Deliberately disable `payroll-agents-enabled` on a test firm after the cycle timer fires → H.1 reconciler flags it within 24h; tenant-admin user task created; dashboard shows it under "Missing".
- [ ] Kill a worker pod mid-run → dashboard surfaces the stuck envelope; CloudWatch alarm fires on consumer lag; canary fails within 30m.
- [ ] A deliberately drifted `mock-payroll` run (via [demo/test-roster-drift.sh](demo/test-roster-drift.sh)) populates the HITL inbox with the `review` payload; dashboard shows SLA-to-acknowledgment ticking.

---

## 10. Integration testing plan

How to validate the full pipeline before touching any real government portal.

### 9.1 Local / dev validation

Agent-worker already has a complete end-to-end test that runs without Praxis:
- `demo/run-pipeline.sh` — runs real AutoPlanilla capture, seeds the mock harness, runs submit. See [agent-worker README] or the script header for usage.
- `demo/test-roster-drift.sh` — reproduces the `MISMATCH` path deterministically by injecting roster drift.

These prove the worker and the contract are correct in isolation.

### 9.2 Praxis-side contract test

- [ ] Write a Praxis integration test that:
  1. Starts an embedded RabbitMQ (TestContainers works well).
  2. Publishes a `PayrollSubmitRequest` with a known fixture body to `financeagent.tasks.submit.mock-payroll`.
  3. Expects a `PayrollSubmitResult` on `financeagent.results` within 60s with matching correlation-id and a valid status.
  - The worker running against this test can be a Docker container (from §F.1) or a locally-launched Spring Boot app.
- [ ] **Acceptance:** the test passes reliably on the CI runner.

### 9.3 Staging rehearsal

- [ ] Before first real CCSS / INS / Hacienda submission, a full end-to-end rehearsal in staging against a test firm + sandbox credentials (if the portals offer them — they typically don't, in which case staging = production-against-a-real-firm, with extra supervision).
- [ ] Rehearsal checklist:
  - KMS key + alias `alias/payroll-firm-<test-firm-id>` provisioned.
  - Secrets Manager populated with portal credentials.
  - All three worker deployments running + consuming from their queues.
  - BPMN process deployed in staging Flowable.
  - Timer firing (or manual trigger) produces a run end-to-end.
  - `payroll-capture-result.v1` and three `payroll-submit-result.v1` envelopes land in the artifact store.
  - Reconciliation status = SUCCESS (or a known-explainable PARTIAL).

---

## 11. Rollout plan

Once Phase 1 is green in staging:

1. **Single-firm pilot.** Enable one firm (NeoProc as the obvious candidate) in the Praxis UI. Run the next monthly cycle under supervision. Expected: at least one PARTIAL result from drift, which exercises the HITL flow.
2. **Monitor for two cycles.** Let the process run twice more (next two months) with the same firm. Look for false-positive MISMATCHes, timing issues, memory leaks.
3. **Onboard additional firms.** Roll out per-firm. Each firm-onboarding provisions its KMS key + portal creds in Secrets Manager; no per-firm code changes should be required. This is the payoff of Phase 1.
4. **Optional: per-portal rate scaling.** If CCSS starts rate-limiting, scale the CCSS worker's concurrency by partitioning the queue (`financeagent.tasks.submit.ccss-sicere.<partition>`). Default one-worker-per-portal is fine until then.

---

## 12. Open questions (please confirm or redirect)

These are assumptions I've made that Praxis may want to adjust. Each is tagged **BLOCKING** (decision must land before Phase 1 development can start in earnest) or **NON-BLOCKING** (decision can be made while other workstreams are in flight, as long as it's settled before the dependent workstream's acceptance test).

1. **[BLOCKING, §C]** RabbitMQ vs other broker. I've assumed RabbitMQ because it's the most common Flowable pairing. If Praxis already uses Kafka or has a different message bus, we adapt the topology but the contract (`PayrollSubmitRequest` / `PayrollSubmitResult` JSON) is unchanged. Blocks all of Workstream C design.
2. **[NON-BLOCKING, §C.4]** Redis vs Postgres for idempotency state. Worker needs a shared store for processed envelopeIds. Pick whichever Praxis already operates. Can be decided before the first staging run; doesn't block the worker runtime change.
3. **[NON-BLOCKING, §F.3]** Artifact storage. Same question — S3-compatible object storage for the run manifests. Whatever Praxis already has. Can be decided before Workstream F's acceptance test; for dev work, local disk is fine.
4. **[BLOCKING, §G]** HITL review UI ownership + design. It's a User Task in BPM (Praxis-side), but the `review` payload shape in Workstream G needs sign-off from whoever owns the finance UX. Blocks the agent-worker's ability to finalize the `review` schema.
5. **[NON-BLOCKING, §B.2]** Firm onboarding flow timing. Do portal credentials get entered at firm-create time, or lazily when a firm first enables payroll agents? The latter is cleaner but requires a UI trigger. Doesn't block Secrets Manager provisioning; can be decided while §B is being built.
6. **[NON-BLOCKING, Phase 2]** Phase 2 kickoff. The CCSS / INS / Hacienda descriptor work can start in parallel with Phase 1 — selector discovery against the live portals doesn't block on the Praxis plumbing. Schedule a session between whoever will write those three descriptors and a payroll admin who can walk through the portals during a live submission window.

**First-week focus for Praxis:** resolve Q1 and Q4. Everything else can proceed in parallel.

---

## 13. Estimation (rough)

| Workstream | Praxis-side effort | Agent-worker-side effort | Parallelizable? |
|---|---|---|---|
| A. AWS KMS envelope encryption | ~1 eng-day | ~1 eng-day (new `KmsEnvelopeCipher`) | yes |
| B. AWS Secrets Manager + creds | ~2 eng-days (includes UI changes) | ~1 eng-day (new provider) | yes |
| C. RabbitMQ + worker runtime | ~2 eng-days | ~5 eng-days (CLI → queue consumer is the biggest delta) | partially |
| D. BPMN wiring | ~3 eng-days | 0 | no (depends on A, B, C) |
| E. Observability | ~1 eng-day | ~1 eng-day | yes |
| F. Packaging | ~2 eng-days (ECS Fargate + IAM setup) | ~1 eng-day (Dockerfile) | yes |
| G. HITL review | ~2 eng-days (user task + review UI) | ~1 eng-day (review payload) | yes, once Q4 resolved |
| H. Supervision (reconciler + dashboard + alerts + canary) | ~5 eng-days | ~half eng-day | yes; H.2 depends on G |
| Integration testing | ~2 eng-days | ~1 eng-day | no (depends on all above) |
| **Total** | **~20 eng-days** | **~12 eng-days** | |

With focused parallelism and no surprises, the critical path is ~3 weeks end-to-end. Phase 2 (the three real portal descriptors) can run alongside starting the week after.
