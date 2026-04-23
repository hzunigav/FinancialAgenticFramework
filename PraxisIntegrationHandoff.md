# Praxis Integration Handoff — Phase 1

Everything the Praxis team needs to build, configure, or decide so the agent-worker can run under Praxis's BPM orchestration in production. **This document is the scope of Phase 1.** Phase 2 (per-portal onboarding for CCSS / INS / Hacienda) and Phase 3 (deploy packaging) are referenced but not expanded here — they're downstream of this work.

**Companion docs (read in this order):**
1. [contract-api/CONTRACT.md](contract-api/CONTRACT.md) — wire format, envelope schemas, encryption spec, and the per-firm Vault transit runbook.
2. [PayrollOrchestrationFlow.md](PayrollOrchestrationFlow.md) — the BPMN process model that wraps the envelopes described in CONTRACT.md.
3. This doc — the plumbing between Praxis and the worker that those two documents assume exists.

---

## 0. Context for the Praxis team

### What this replaces

The Praxis roadmap previously listed an **Agentic AI Microservice** feature — a Python/FastAPI service for headless browser scraping, LLM-powered extraction, and Vault credential management. **That feature is replaced by the agent-worker described in this document** and should be removed from the Praxis backlog (or redefined as "agent-worker runtime — external service, integrated via envelope contract").

Specifically:
- **Headless browser scraping** is handled by [agent-worker](agent-worker/), a Java service using Playwright with declarative YAML portal descriptors. One descriptor per portal, versioned in git, auditable step-by-step.
- **Vault credential management** is covered by Workstreams A and B in this document, with a single source of truth (see §3.0 below).
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
| Encryption key management (Vault transit + per-firm keys) | ✓ | |
| Portal credential storage (Vault KV) | ✓ | |
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

## 2. Workstream A — Vault transit engine

**Goal:** enable field-level encryption/decryption of envelope bodies using per-firm keys.

**Reference:** [CONTRACT.md §4 "Production (Praxis-side runbook)"](contract-api/CONTRACT.md#4-encryption) has the detailed steps. Summary:

### A.1 Mount the transit engine

- [ ] Enable the transit secrets engine in the existing Vault deployment.
  ```
  vault secrets enable transit
  ```
- [ ] Confirm the Praxis service account has a policy allowing `transit/encrypt/payroll-firm-*` and `transit/decrypt/payroll-firm-*` paths.
  - Recommended policy name: `financeagent-payroll-transit`.
  - Agent-worker containers will use a separate policy (see §B.3) that can only **decrypt** under a specific firm — never encrypt.

### A.2 Provision a key at firm-onboarding time

- [ ] Hook into the existing firm-onboarding flow. Suggested integration: a new `FirmOnboardingListener` Spring bean that consumes `FirmCreatedEvent` (or whatever event Praxis emits today when a firm is created) and calls:
  ```
  POST /v1/transit/keys/payroll-firm-<firmId>
  ```
  This endpoint is idempotent on the Vault side — re-onboarding a firm will not regenerate or rotate the key.
- [ ] **Acceptance:** after `FirmCreatedEvent` for firmId=99999, `vault read transit/keys/payroll-firm-99999` returns metadata (not 404).

### A.3 Implement `VaultTransitClient` in Praxis

- [ ] Add Spring `WebClient` configured against Vault base URL + token. (Full stack Spring Cloud Vault is overkill — we only need encrypt/decrypt.)
- [ ] Expose two methods:
  ```java
  String encrypt(long firmId, byte[] plaintext);   // returns "vault:v3:..."
  byte[] decrypt(long firmId, String ciphertext);  // accepts "vault:v3:..."
  ```
- [ ] The wire format is exactly what the worker emits under `local-aes-gcm-v1` today — no translation layer needed.
- [ ] **Acceptance:** given a capture envelope produced by the worker in dev (`encryption.scheme=local-aes-gcm-v1`) and the matching Vault transit key provisioned, Praxis's `VaultTransitClient.decrypt()` returns the same cleartext body that the worker's `LocalDevCipher.decrypt()` would produce. This proves the wire formats are interchangeable.

### A.4 Switch the worker to `vault-transit-v1`

- [ ] Once A.1–A.3 are green in a staging Vault, the worker is flipped via env var: `FINANCEAGENT_CIPHER=vault`. The existing `VaultTransitCipher` class in `common-lib` needs its `throw new UnsupportedOperationException` replaced with the live REST call. Small change (~30 lines), agent-worker side, after Praxis confirms A.3.

---

## 3. Workstream B — Vault KV for portal credentials

**Goal:** eliminate the dev-only `~/.financeagent/secrets.properties` file. Portal credentials live in Vault, with the right scope for each portal's real-world access model.

**Today (dev):** `LocalFileCredentialsProvider` reads `portals.<portalId>.credentials.<name>=<value>` from a flat properties file. Agent looks up credentials by portalId only — no firm scoping, no concept of shared vs per-firm creds.

**Production:** credentials scope depends on the portal. Two models exist in the wild:

| Scope | Example portals | Real-world meaning |
|---|---|---|
| **per-firm** | CCSS Sicere | Each client firm has its own patrono-level login. NeoProc logs in *as the firm*; a different firm = different credentials. |
| **shared** | INS RT-Virtual, Hacienda OVI, AutoPlanilla | A single NeoProc-owned login covers every client firm. After login, the agent picks the target client on the portal page using a firm-specific identifier (cédula jurídica / client code). |

### B.0 Centralization — one source of truth

A legitimate concern when two services need credentials is where they're entered and who owns them. The design here is deliberately centralized:

- **One UI for entry.** The Praxis firm-admin UI is the *only* place a human ever enters portal credentials. Agent-worker has no UI. Admins never touch a worker container or a secrets file.
- **One store.** All credentials (portal creds for the agent-worker, plus whatever credentials Praxis uses for its own external integrations) live in the same Vault instance. Separate mounts for separation of concerns (`financeagent/` for this work; Praxis keeps whatever mount it uses today), but operationally it's one Vault with one UI in front of it.
- **Two readers, narrowly scoped.** Praxis reads (and writes) via its own AppRole with a policy allowing UI-driven writes. The agent-worker reads via its own AppRole with a read-only policy scoped to the `financeagent/` subtrees below. Neither service can read the other's credentials. This is the standard Vault pattern; think of the two AppRoles like two database users with different grants on the same database.
- **One admin flow per portal, not per firm-portal combination.** For shared-creds portals, NeoProc enters credentials *once* (at tenant level), not N times (once per onboarded firm). Rotation touches one path. The per-firm metadata (which client to pick after login) is captured separately and is *not* a secret.

Why not push credentials into the envelope itself and skip the worker's Vault integration entirely? We considered it. It's feasible (credentials get encrypted with the per-firm transit key and ride the envelope), but the tradeoffs land wrong: credentials end up in flight on the message bus on every run, Praxis becomes the critical path for every worker action, and the blast radius on a broker compromise is larger. A read-only AppRole on the worker side is ~15 lines of Spring code and one small policy — strictly cleaner.

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
                                   ┌─────────────┐
                                   │   Vault     │
                                   │  (single)   │
                                   └─────────────┘
                                          ▲
                                          │
   ┌─────────────────────┐                │
   │   Agent-worker      │──── reads ─────┘
   │   (scope chosen per │
   │    descriptor)      │
   └─────────────────────┘
```

So: one place to enter, one place to rotate, one audit trail. No duplication.

### B.1 Vault KV mount + layout

- [ ] Mount a KV v2 secrets engine. Suggested path: `financeagent/` (parallel to the existing transit mount).
- [ ] **Two subtrees, one per credential scope:**
  ```
  # Per-firm — one record per (firmId, portalId). Used by: CCSS Sicere.
  financeagent/firms/<firmId>/portals/<portalId>
    -> { username: "...", password: "...", mfaMethod: "totp|sms|none", mfaSecret: "..." }

  # Shared — one record per portalId, reused across every firm in this
  # Praxis tenant. Used by: INS RT-Virtual, Hacienda OVI, AutoPlanilla.
  financeagent/shared/portals/<portalId>
    -> { username: "...", password: "...", mfaMethod: "totp|sms|none", mfaSecret: "..." }
  ```
- [ ] **Acceptance:** `vault kv get financeagent/firms/12345/portals/ccss-sicere` returns firm 12345's CCSS login; `vault kv get financeagent/shared/portals/ins-rt-virtual` returns NeoProc's shared INS login. Confirming both paths are reachable under the tenant's Vault policy.

### B.2 Firm onboarding UI

Two workflows, depending on portal scope:

- [ ] **Per-firm portals.** Existing pattern: when a firm is onboarded (or when payroll agents are enabled for an already-onboarded firm), the admin enters credentials per firm per portal. UI writes to `financeagent/firms/<firmId>/portals/<portalId>`.
- [ ] **Shared portals (tenant-level credentials).** NeoProc enters credentials once at tenant setup time (not per firm). UI writes to `financeagent/shared/portals/<portalId>`. The credentials form is only shown to users with tenant-admin role.
- [ ] **Shared portals (per-firm client identifier).** Separately, each firm's record gets a **client identifier** field per shared portal — what the portal indexes that firm by (cédula jurídica, internal client code, whatever). Stored in Praxis's firm record, not Vault. This is **not a secret**; it's firm metadata that travels with the envelope.
- [ ] **Envelope carries the client identifier for shared-creds runs.** Extend the submit envelope's `task` block with an optional `clientIdentifier` field. Praxis populates it from the firm's record when dispatching to a shared-creds portal. The worker feeds it into the descriptor as `${params.clientIdentifier}`, and the descriptor's `steps` block uses it to pick the right client on the post-login page:
  ```yaml
  steps:
    - action: select               # or click / fill, depending on portal UI
      selector: 'role=combobox[name="Cliente"]'
      value: "${params.clientIdentifier}"
    - action: ...                  # then the normal per-firm flow
  ```
  See also the `credentialScope: shared` declaration in the portal descriptor ([autoplanilla.yaml](agent-worker/src/main/resources/portals/autoplanilla.yaml) is a live example) — the worker uses this to know which Vault subtree to read.
- [ ] **MFA handling:** out of scope for Phase 1 if none of the three target portals require it. If any do — CCSS likely does — see [PortalOnboarding.md](PortalOnboarding.md) for how the worker's `pause` action lets an operator enter MFA codes interactively. Revisit once the Phase 2 team reports back.

### B.3 Credentials provider swap on the worker

- [ ] Agent-worker side: add a `VaultCredentialsProvider` in `common-lib` that implements the existing `CredentialsProvider` interface. On lookup, it reads the target portal's descriptor, checks `credentialScope`, and fetches from either `financeagent/firms/<firmId>/portals/<portalId>` (per-firm) or `financeagent/shared/portals/<portalId>` (shared). The descriptor-driven scope avoids any runtime guessing.
- [ ] Worker picks the provider via env var:
  ```
  FINANCEAGENT_CREDENTIALS=local    (default — LocalFileCredentialsProvider)
  FINANCEAGENT_CREDENTIALS=vault    (VaultCredentialsProvider)
  ```
- [ ] Worker identifies itself to Vault using an AppRole tied to the agent-worker's deployment identity. Policy grants read-only on **both** subtrees — `financeagent/firms/+/portals/+` AND `financeagent/shared/portals/+` — and nothing else. The descriptor's `credentialScope` constrains which path is actually hit per run.

### B.4 Scoping enforcement

- [ ] The worker uses the `envelope.firmId` from the incoming `PayrollSubmitRequest` as the authoritative firm scope. It must not accept a firmId from any other source (system property, env, etc.).
- [ ] For shared-creds portals, the worker uses `task.clientIdentifier` from the envelope as the authoritative per-firm selector on the portal page. The worker does not derive the identifier from the firmId — Praxis is the source of truth for which portal-side identifier maps to which firm.
- [ ] **Acceptance (per-firm):** a worker receiving an envelope with `firmId=12345` targeting a per-firm portal cannot read credentials under `financeagent/firms/67890/...` even if the envelope body claims otherwise.
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
  3. Fetch credentials from Vault scoped to `envelope.firmId` + portal descriptor's id.
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
  2. Encrypts the body via `VaultTransitClient.encrypt(firmId, ...)`.
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
  - Vault AppRole credentials (for credentials fetch + transit decrypt).
  - RabbitMQ connection string (mounted secret).
  - `FINANCEAGENT_CIPHER=vault`, `FINANCEAGENT_CREDENTIALS=vault`.
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

## 9. Integration testing plan

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
  - Vault transit keys provisioned for the test firm.
  - Vault KV populated with portal credentials.
  - All three worker deployments running + consuming from their queues.
  - BPMN process deployed in staging Flowable.
  - Timer firing (or manual trigger) produces a run end-to-end.
  - `payroll-capture-result.v1` and three `payroll-submit-result.v1` envelopes land in the artifact store.
  - Reconciliation status = SUCCESS (or a known-explainable PARTIAL).

---

## 10. Rollout plan

Once Phase 1 is green in staging:

1. **Single-firm pilot.** Enable one firm (NeoProc as the obvious candidate) in the Praxis UI. Run the next monthly cycle under supervision. Expected: at least one PARTIAL result from drift, which exercises the HITL flow.
2. **Monitor for two cycles.** Let the process run twice more (next two months) with the same firm. Look for false-positive MISMATCHes, timing issues, memory leaks.
3. **Onboard additional firms.** Roll out per-firm. Each firm-onboarding writes its Vault keys + portal creds; no per-firm code changes should be required. This is the payoff of Phase 1.
4. **Optional: per-portal rate scaling.** If CCSS starts rate-limiting, scale the CCSS worker's concurrency by partitioning the queue (`financeagent.tasks.submit.ccss-sicere.<partition>`). Default one-worker-per-portal is fine until then.

---

## 11. Open questions (please confirm or redirect)

These are assumptions I've made that Praxis may want to adjust. Each is tagged **BLOCKING** (decision must land before Phase 1 development can start in earnest) or **NON-BLOCKING** (decision can be made while other workstreams are in flight, as long as it's settled before the dependent workstream's acceptance test).

1. **[BLOCKING, §C]** RabbitMQ vs other broker. I've assumed RabbitMQ because it's the most common Flowable pairing. If Praxis already uses Kafka or has a different message bus, we adapt the topology but the contract (`PayrollSubmitRequest` / `PayrollSubmitResult` JSON) is unchanged. Blocks all of Workstream C design.
2. **[NON-BLOCKING, §C.4]** Redis vs Postgres for idempotency state. Worker needs a shared store for processed envelopeIds. Pick whichever Praxis already operates. Can be decided before the first staging run; doesn't block the worker runtime change.
3. **[NON-BLOCKING, §F.3]** Artifact storage. Same question — S3-compatible object storage for the run manifests. Whatever Praxis already has. Can be decided before Workstream F's acceptance test; for dev work, local disk is fine.
4. **[BLOCKING, §G]** HITL review UI ownership + design. It's a User Task in BPM (Praxis-side), but the `review` payload shape in Workstream G needs sign-off from whoever owns the finance UX. Blocks the agent-worker's ability to finalize the `review` schema.
5. **[NON-BLOCKING, §B.2]** Firm onboarding flow timing. Do portal credentials get entered at firm-create time, or lazily when a firm first enables payroll agents? The latter is cleaner but requires a UI trigger. Doesn't block Vault KV provisioning; can be decided while §B is being built.
6. **[NON-BLOCKING, Phase 2]** Phase 2 kickoff. The CCSS / INS / Hacienda descriptor work can start in parallel with Phase 1 — selector discovery against the live portals doesn't block on the Praxis plumbing. Schedule a session between whoever will write those three descriptors and a payroll admin who can walk through the portals during a live submission window.

**First-week focus for Praxis:** resolve Q1 and Q4. Everything else can proceed in parallel.

---

## 12. Estimation (rough)

| Workstream | Praxis-side effort | Agent-worker-side effort | Parallelizable? |
|---|---|---|---|
| A. Vault transit | ~2 eng-days | ~1 eng-day (swap `UnsupportedOperationException`) | yes |
| B. Vault KV + creds | ~3 eng-days (includes UI changes) | ~2 eng-days (new provider) | yes |
| C. RabbitMQ + worker runtime | ~2 eng-days | ~5 eng-days (CLI → queue consumer is the biggest delta) | partially |
| D. BPMN wiring | ~3 eng-days | 0 | no (depends on A, B, C) |
| E. Observability | ~1 eng-day | ~1 eng-day | yes |
| F. Packaging | ~2 eng-days (K8s + AppRole setup) | ~1 eng-day (Dockerfile) | yes |
| G. HITL review | ~2 eng-days (user task + review UI) | ~1 eng-day (review payload) | yes, once Q4 resolved |
| Integration testing | ~2 eng-days | ~1 eng-day | no (depends on all above) |
| **Total** | **~17 eng-days** | **~12 eng-days** | |

With focused parallelism and no surprises, the critical path is ~3 weeks end-to-end. Phase 2 (the three real portal descriptors) can run alongside starting the week after.
