# Implementation Plan: Financial Agentic Framework

Living document. Source of truth for *where we are* vs. *what's next*.
Complements [DeploymentPlan.md](DeploymentPlan.md) (infra go-live), [TechnicalRequirements.md](TechnicalRequirements.md) (the spec), [EnhancementsBacklog.md](EnhancementsBacklog.md) (post-production improvements), and [PortalOnboarding.md](PortalOnboarding.md) (how to add a new portal).

**Legend:** ✓ done · → in progress · · planned

---

## Where we are

| | Milestone | Status |
|---|---|---|
| M1 | Mock vertical slice | ✓ |
| M2 | Portal adapter + Read-Back verifier | ✓ |
| M2.5 | Credentials provider + HAR scrubbing | ✓ |
| M3.0 | Engine plumbing for MFA + session + shadow | ✓ |
| M3 | Real portal access (Shadow Mode, fill-only) | ✓ |
| P1 | Praxis Phase 1 integration — agent-worker readiness | ✓ |
| M4 | MCP Server + real payroll data feed | · |
| M5 | HITL gate + Safe-Submit loop | · |
| M6 | Production AWS infra | · |
| M7 | Exception-only autonomy | · |

---

## M1 — Mock vertical slice · ✓

**Goal:** Prove the full stack from browser login through audit artifacts, locally, on the user's known stack (Spring Boot + Java).

**Delivered:**
- Monorepo: `common-lib`, `contract-api`, `mcp-payroll-server`, `agent-gateway`, `agent-worker`, `testing-harness`, `infra-aws` (placeholder).
- Spring Boot mock portal with form login, CSRF, session cookies — [testing-harness/](../testing-harness/).
- Playwright agent drives login end-to-end — [agent-worker/](../agent-worker/).
- Per-run audit bundle: `trace.zip`, `network.har`, `report.png`, `manifest.json`.
- Tomcat access log from mock portal for independent server-side audit.

**Commits:** `5a0123c`, `65db252`.

---

## M2 — Portal adapter + Read-Back verifier · ✓

**Goal:** Externalize portal-specific logic so a new portal is a new YAML file, not new Java. Add deterministic verification per spec §7.2.

**Delivered:**
- `PortalDescriptor` YAML schema + loader — [PortalDescriptor.java](../agent-worker/src/main/java/com/financialagent/worker/portal/PortalDescriptor.java), [PortalDescriptorLoader.java](../agent-worker/src/main/java/com/financialagent/worker/portal/PortalDescriptorLoader.java).
- `PortalEngine` executes declarative steps (`navigate` / `fill` / `click` / `waitForUrl`) with `${credentials.*}` placeholder resolution and value redaction — [PortalEngine.java](../agent-worker/src/main/java/com/financialagent/worker/portal/PortalEngine.java).
- `PortalScraper` extracts a name→text map using Playwright strict locators (spec §5) — [PortalScraper.java](../agent-worker/src/main/java/com/financialagent/worker/portal/PortalScraper.java).
- `ReadBackVerifier<T extends Record>` — reflection-based field diff, portal-agnostic, reusable for every future domain record — [ReadBackVerifier.java](../common-lib/src/main/java/com/financialagent/common/verify/ReadBackVerifier.java).
- First portal descriptor: [mock-portal.yaml](../agent-worker/src/main/resources/portals/mock-portal.yaml).
- 5 unit tests covering MATCH / one-field-MISMATCH / all-fields-MISMATCH / type-mismatch / null-guard — [ReadBackVerifierTest.java](../common-lib/src/test/java/com/financialagent/common/verify/ReadBackVerifierTest.java).
- Verification result recorded in [manifest.json](../agent-worker/artifacts/).

**Run evidence:** `agent-worker/artifacts/20260422T135445-78418/manifest.json` — MATCH on `(username, reportDate)`.

---

## M2.5 — Credentials provider + HAR scrubbing · ✓

**Goal:** Gate the repo against real credentials before they arrive. The framework must be safe to hand a prod password on day one of M3.

**Delivered:**
- `CredentialsProvider` interface + `PortalCredentials` record in `common-lib` — [CredentialsProvider.java](../common-lib/src/main/java/com/financialagent/common/credentials/CredentialsProvider.java), [PortalCredentials.java](../common-lib/src/main/java/com/financialagent/common/credentials/PortalCredentials.java). Same interface will back the M6 `AwsSecretsManagerCredentialsProvider`.
- `LocalFileCredentialsProvider` — reads `~/.financeagent/secrets.properties` (override via `FINANCEAGENT_SECRETS_FILE`), refuses to load if the file grants read access to anyone but the owner (POSIX or ACL) — [LocalFileCredentialsProvider.java](../common-lib/src/main/java/com/financialagent/common/credentials/LocalFileCredentialsProvider.java).
- Mock credentials removed from committed [config.properties](../agent-worker/src/main/resources/config.properties). Template at repo root: [secrets.properties.example](../secrets.properties.example).
- `securityContext.scrubHarFields` block in [PortalDescriptor.java](../agent-worker/src/main/java/com/financialagent/worker/portal/PortalDescriptor.java) — declarative, per-portal list of URL patterns + body field names to redact in the HAR.
- [HarScrubber.java](../agent-worker/src/main/java/com/financialagent/worker/portal/HarScrubber.java) — post-processes `network.har` after context close. Handles `application/x-www-form-urlencoded` (both the `params` array and the raw `text`) and `application/json` (recursive). Falls back to stripping `postData` entirely for unknown content types — fail-closed.
- Repo pre-commit hook at [.githooks/pre-commit](../.githooks/pre-commit) — blocks any commit introducing a `portals.<id>.credentials.*=*` line for anything other than `mock-portal`, and blocks any file named `secrets.properties`. Activate once per clone: `git config core.hooksPath .githooks`.

**Run evidence:** `agent-worker/artifacts/20260422T142602-e04f2/network.har` — login POST body contains `password=%5BREDACTED%5D` (the URL-encoded form of `[REDACTED]`), and `grep "password=password" network.har manifest.json` returns nothing.

---

## M3.0 — Engine plumbing for MFA + session + shadow · ✓

**Goal:** Land the portal-agnostic mechanisms M3 needs (pause for SMS/email MFA, encrypted session reuse, shadow-mode guard) ahead of real-portal access, so the portal-specific work in M3 is just a descriptor + domain record.

**Delivered:**
- `pause` action in `PortalEngine` — halts, prompts the operator (stdin in dev), injects the response into bindings under a `bindTo` key, audits the prompt but never the response — [PortalEngine.java](../agent-worker/src/main/java/com/financialagent/worker/portal/PortalEngine.java).
- Shadow-mode guard — top-level `shadowMode: true` in descriptor + per-step `submit: true` flag; guard throws `PortalEngine.ShadowHalt` before touching the page, caller marks the run `SHADOW_HALT` rather than `FAILED` or `SUCCESS`. Unit-tested — [PortalEngineShadowModeTest.java](../agent-worker/src/test/java/com/financialagent/worker/portal/PortalEngineShadowModeTest.java).
- `SessionStore` interface + `LocalEncryptedSessionStore` in `common-lib` — AES-256-GCM, per-portal `.enc` blob at `~/.financeagent/sessions/<portalId>.enc`, key at `~/.financeagent/session-key`. TTL declared per-portal via `session.ttlMinutes`. 8 unit tests: round-trip, no-plaintext-on-disk, TTL expiry purges, missing-file, purge, per-portal isolation, key reuse, IV randomness — [LocalEncryptedSessionStoreTest.java](../common-lib/src/test/java/com/financialagent/common/session/LocalEncryptedSessionStoreTest.java).
- Descriptor restructured from a flat `steps` list into `authSteps` (pre-auth; skipped when a valid session is loaded) and `steps` (post-auth; always run) — [PortalDescriptor.java](../agent-worker/src/main/java/com/financialagent/worker/portal/PortalDescriptor.java).
- Windows ACL check relaxed to ignore privileged system principals (SYSTEM, Administrators, LOCAL SERVICE, NETWORK SERVICE) — they're granted access to everything in a user profile by default and excluding them fought the platform rather than the threat model.
- Manifest records `portal.shadowMode` and `portal.sessionReused` so auditors can tell at a glance whether a run went through login or reused a session — [RunManifest.java](../agent-worker/src/main/java/com/financialagent/worker/RunManifest.java).

**Run evidence:** consecutive runs `20260422T145923-c66c1` (Session reused: false, authSteps ran) and `20260422T145929-d6309` (Session reused: true, first manifest step is `auth-skipped | session-reused`, scrape+verify still MATCH). HAR remains scrubbed across both runs.

---

## M3 — Real portal access (Shadow Mode) · ✓

**Portal:** AutoPlanilla (`https://app.autoplanilla.com`) — shared credentials (NeoProc login), planilla selected via `params.planillaName`.

**Goal:** Exercise the framework against a real site with zero write-risk. Spec Phase 1.

**Delivered:**
- `PayrollSummary` + `PayrollEmployeeRow` domain records in `common-lib` — BigDecimal amounts, employee id/name/grossSalary, CRC currency fields.
- `autoplanilla.yaml` descriptor — `authSteps` (navigate → fill → click), `steps` (navigate to CCSS Report, date-range selects), `scrape` (totals + per-row Material React Table with 30-row cap), `securityContext.scrubHarFields` for login payload — [autoplanilla.yaml](../agent-worker/src/main/resources/portals/autoplanilla.yaml).
- `AutoplanillaMapper` — CRC currency parser (`₡`/`CRC` prefix, comma grouping), `"of N"` pagination-footer employee-count parser — [AutoplanillaMapper.java](../agent-worker/src/main/java/com/neoproc/financialagent/worker/AutoplanillaMapper.java).
- `AutoplanillaAdapter` — capture-only; builds `PayrollSummary`, emits `payroll-capture-result.v1` envelope — [AutoplanillaAdapter.java](../agent-worker/src/main/java/com/neoproc/financialagent/worker/AutoplanillaAdapter.java).
- `AbstractCaptureAdapter` / `AbstractSubmitAdapter` base classes — envelope emission, encryption, audit hashing — reusable for CCSS Sicere, INS, Hacienda.
- Payroll data successfully captured from the real AutoPlanilla portal in shadow mode.

---

## P1 — Praxis Phase 1 integration (agent-worker) · in progress

**Goal:** Land the agent-worker-side deltas enumerated in [PraxisIntegrationHandoff.md](PraxisIntegrationHandoff.md) so the moment Praxis ships Workstreams A/B/C, production cutover is an env-var flip (`FINANCEAGENT_CIPHER=kms`, `FINANCEAGENT_CREDENTIALS=aws`), not a code change. Does not depend on real-portal credentials — runs in parallel with M3.

**Planned work (sequenced for parallel PRs):**

1. **`KmsEnvelopeCipher` in `common-lib`** — ~50-line wrapper around AWS SDK v2 `KmsClient` implementing the existing `EnvelopeCipher` interface. Dev runs unchanged (`local-aes-gcm-v1`); production path gated by `FINANCEAGENT_CIPHER=kms`. Tested against LocalStack. Handoff §A.4.
2. **`AwsSecretsManagerCredentialsProvider` in `common-lib`** — implements existing `CredentialsProvider`; reads the descriptor's `credentialScope` to pick `financeagent/firms/<firmId>/portals/<portalId>` vs `financeagent/shared/portals/<portalId>`. Wired via `FINANCEAGENT_CREDENTIALS=aws`. Handoff §B.3.
3. **Per-portal `rateLimit` descriptor field + worker enforcement** — pulled forward from [EnhancementsBacklog.md](EnhancementsBacklog.md). `rateLimit: { maxConcurrent, minIntervalSeconds }` on `PortalDescriptor`; portal-keyed semaphore + Guava `RateLimiter` in `PortalEngine`. Handoff §C.5.
4. **`review` block on `PayrollSubmitResult`** — pulled forward from [EnhancementsBacklog.md](EnhancementsBacklog.md). Extend `SubmitResultBody` with `{ severity, summary, signals[], allowedActions[] }`; adapters populate from existing roster-diff / reconciliation data. Handoff §G.1.
5. **Correlation-ID logging** — thread `envelopeId` / `businessKey` / `issuerRunId` into every log line (MDC) and into `manifest.json`. Handoff §E.1.
6. **CLI → Spring Boot queue consumer** — biggest delta. Replace `Agent.main()` with a `@RabbitListener`-driven Spring Boot app; Postgres-backed idempotency table keyed on `envelopeId` (7-day TTL); ack on success, nack-requeue=false on unrecoverable errors. Tested end-to-end against TestContainers RabbitMQ. Handoff §C.3 + §C.4.
7. **Dockerfile** — multi-stage on `mcr.microsoft.com/playwright/java:<current-stable>`; `docker run` against a local Rabbit processes a test envelope. Handoff §F.1.

**Supervision hooks (feed Praxis Workstream H — see handoff §9) — Delivered:**
- **Prometheus metrics** — `agent_capture_duration_seconds` and `agent_submit_duration_seconds` (Timer, tags `portal` + `status`) recorded in `PortalRunService.run()` finally block via `Metrics.globalRegistry` (no-op in CLI mode, bound to `PrometheusMeterRegistry` in Spring Boot mode). `agent_reconciliation_gap_colones` (DistributionSummary) and `agent_roster_diff_size` (Counter, tag `bucket=missingFromPortal|missingFromPayroll`) recorded in `AbstractSubmitAdapter.recordSubmitMetrics()` while the cleartext body is in scope, extracting gap from the `TOTAL_GAP` signal (0 for SUCCESS) and sizes from `RosterDiff` — [PortalRunService.java](../agent-worker/src/main/java/com/neoproc/financialagent/worker/PortalRunService.java), [AbstractSubmitAdapter.java](../agent-worker/src/main/java/com/neoproc/financialagent/worker/AbstractSubmitAdapter.java).
- Structured terminal-state log line per run: one INFO entry with final status (`SUCCESS` / `PARTIAL` / `FAILED` / `MISMATCH`) + the correlation triple — already delivered with MDC logging (Item 5).
- `mock-payroll` descriptor deployable in the production worker image — already included in the worker image (no dev-only flags).

**Delivered (E2E integration fixes — discovered during Praxis workstream review):**
- **Submit queue TTL removed** — agent-worker had `.ttl(3_600_000)` on the submit queue bean; Praxis declares no TTL. Removed to avoid `PRECONDITION_FAILED` on startup — [RabbitMqConfig.java](../agent-worker/src/main/java/com/neoproc/financialagent/worker/config/RabbitMqConfig.java).
- **Capture listener** — BPMN first step publishes to per-portal capture queues (`financeagent.tasks.capture.${PORTAL_ID}`); agent-worker had no listener. Added: `PayrollCaptureRequest` type in `contract-api`, `PayrollCaptureListener` subscribing to `${agent.worker.capture-queue}`, capture queue + binding in `RabbitMqConfig`. A single `PORTAL_ID=mock-payroll` worker now covers both steps — [PayrollCaptureRequest.java](../contract-api/src/main/java/com/neoproc/financialagent/contract/payroll/PayrollCaptureRequest.java), [PayrollCaptureListener.java](../agent-worker/src/main/java/com/neoproc/financialagent/worker/listener/PayrollCaptureListener.java).

**Delivered (Items 1–7):**
- **Dockerfile** — two-stage build: `maven:3.9-eclipse-temurin-21-jammy` compiles the `agent-worker` sub-reactor (`-pl agent-worker -am`) with POM-layer caching for fast incremental rebuilds; `mcr.microsoft.com/playwright/java:v1.48.0-jammy` runtime image carries Chromium/Firefox/WebKit at `/ms-playwright` and OpenJDK 21 with `pwuser` (uid 1000) as the non-root process owner. `PORTAL_ID`, `RABBITMQ_*`, `FINANCEAGENT_CIPHER/CREDENTIALS` are all env-var-injected; production values (`kms` / `aws`) override the dev defaults at ECS task-definition time (Handoff §F.2). `docker-compose.yml` at repo root spins up `rabbitmq:3.12-management` + the worker for local acceptance testing — [Dockerfile](../Dockerfile), [docker-compose.yml](../docker-compose.yml).

**Delivered (Items 1–6):**
- `KmsEnvelopeCipher` — AES-256-GCM envelope encryption via AWS KMS GenerateDataKey; wire format `kms:v1:<b64 encDEK>.<b64 IV+CT>`; plaintext DEK zeroed in `finally`; `FINANCEAGENT_CIPHER=kms` wired into `EnvelopeIo.defaultCipher()` — [KmsEnvelopeCipher.java](../common-lib/src/main/java/com/neoproc/financialagent/common/crypto/KmsEnvelopeCipher.java).
- `AwsSecretsManagerCredentialsProvider` — resolves `financeagent/shared/portals/<id>` vs `financeagent/firms/<firmId>/portals/<id>` from descriptor `credentialScope`; parses JSON secret; `FINANCEAGENT_CREDENTIALS=aws` wired into `Agent.buildCredentialsProvider()` — [AwsSecretsManagerCredentialsProvider.java](../common-lib/src/main/java/com/neoproc/financialagent/common/credentials/AwsSecretsManagerCredentialsProvider.java).
- `RateLimit` record on `PortalDescriptor` + `PortalRateLimiter` — semaphore (maxConcurrent) + Guava RateLimiter (minIntervalSeconds), portal-keyed static registry, try-with-resources `Permit`; `autoplanilla.yaml` set to `maxConcurrent:1 / minIntervalSeconds:5`; wired into `Agent.main()` — [PortalRateLimiter.java](../agent-worker/src/main/java/com/neoproc/financialagent/worker/portal/PortalRateLimiter.java).
- `Review` + `Signal` on `SubmitResultBody` — structured HITL block for MISMATCH/PARTIAL runs; `Signal` discriminates `TOTAL_GAP / MISSING_FROM_PORTAL / MISSING_FROM_PAYROLL` with `@JsonInclude(NON_NULL)` per-type fields; `allowedActions` always `[RESUBMIT, ACKNOWLEDGE, ESCALATE]`; `MockPayrollAdapter` populates from existing roster-diff/reconciliation data — [SubmitResultBody.java](../contract-api/src/main/java/com/neoproc/financialagent/contract/payroll/SubmitResultBody.java).
- SLF4J/Logback MDC correlation-ID logging — `issuerRunId`, `firmId`, `businessKey`, `envelopeId` threaded into every log line via MDC; Logback pattern `ts=... level=... issuerRunId=... firmId=... businessKey=... envelopeId=... msg="..."`; MDC set in `Agent.main()` at run start and updated by `AbstractSubmitAdapter`/`AbstractCaptureAdapter` at envelope emit; `envelopeId` + `businessKey` also written to `manifest.json` — [logback.xml](../agent-worker/src/main/resources/logback.xml).
- **CLI → Spring Boot queue consumer** — `Agent.main()` refactored to a thin CLI wrapper delegating to `PortalRunService` (plain Java, no Spring, creates a fresh adapter per run to prevent mutable-state contamination across concurrent messages). `WorkerApplication` (`@SpringBootApplication`) is the production entry point. `PayrollTaskListener` (`@RabbitListener`) subscribes to `financeagent.tasks.submit.${portal.id}`, checks `InMemoryIdempotencyStore` (Caffeine, 7-day TTL — swap to Redis/Postgres at M6 with zero caller changes), calls `PortalRunService.run()`, reads `payroll-submit-result.v1.json` from the run directory, and publishes to `financeagent.results` with `correlation-id = envelope.businessKey`. Acks on normal return; nacks (requeue=false → DLQ) only when even the error-result publish fails. Full exchange/queue topology from Handoff §C.1 declared in `RabbitMqConfig`; `/actuator/prometheus` exposed on port 8080. Tested end-to-end against TestContainers RabbitMQ with `@MockBean PortalRunService` — [WorkerApplication.java](../agent-worker/src/main/java/com/neoproc/financialagent/worker/WorkerApplication.java), [PayrollTaskListener.java](../agent-worker/src/main/java/com/neoproc/financialagent/worker/listener/PayrollTaskListener.java), [PortalRunService.java](../agent-worker/src/main/java/com/neoproc/financialagent/worker/PortalRunService.java), [InMemoryIdempotencyStore.java](../agent-worker/src/main/java/com/neoproc/financialagent/worker/idempotency/InMemoryIdempotencyStore.java).

**Sequencing:** Items 1 and 2 are independent and tiny (~1 day each) — land first, in parallel. Items 3–5 are each ~1 day and independent; land in any order once 1–2 are in. Item 6 is the ~5-day block — start once 1–5 are merged so the consumer picks up the new envelope shape, rate-limiting, and correlation-ID logging from day one. Item 7 after 6.

**Exit criteria:**
- `FINANCEAGENT_CIPHER=kms` round-trips encrypt/decrypt against a LocalStack KMS alias.
- `FINANCEAGENT_CREDENTIALS=aws` resolves per-firm and shared paths based on descriptor `credentialScope`.
- Worker consumes `payroll-capture-request.v1` from `financeagent.tasks.capture.mock-payroll` and emits `payroll-capture-result.v1` to `financeagent.results`; duplicate `envelopeId` returns prior result.
- Worker consumes `payroll-submit-request.v1` from `financeagent.tasks.submit.mock-payroll` and emits `payroll-submit-result.v1` with matching `correlation-id`; duplicate `envelopeId` returns the prior result without re-running portal actions.
- Full BPMN cycle (capture → submit) completes end-to-end with `PORTAL_ID=mock-payroll` against Praxis staging.
- Docker image processes a mock envelope on a fresh host.
- `/actuator/prometheus` exposes the four spec metrics during a run.

---

## M4 — MCP Server + real payroll data feed · planned

**Goal:** Data flows from a typed source, not `config.properties`. Spec Phase 2.

**Planned work:**
- Stand up `mcp-payroll-server` (Spring Boot) with paginated, idempotent tool endpoints per spec §4 (context-window safe for 500+ employee batches).
- Build `agent-gateway` (Spring Boot + LangChain4j): LLM orchestrator that pulls `PayrollData` from MCP and hands the worker a task envelope (payload + SHA-256 hash + portal id).
- Worker re-verifies the payload hash on ingestion (spec §5).
- Read-Back compares scraped values against MCP-sourced source-of-truth — no more hand-built records.
- **Cross-cutting landing here:** Java-native PII redactor (spec §7.3) in `common-lib`; OpenTelemetry wiring (spec §7.1) across Gateway→Worker→LLM.

**Exit criteria:** End-to-end MCP → Gateway → Worker → Portal → Read-Back, still Shadow.

---

## M5 — HITL gate + Safe-Submit loop · planned

**Goal:** Four-eyes approval before anything hits Submit. Spec Phase 3.

**Planned work (local substitutes first, AWS swap-in at M6):**
- Spring AMQP or Spring event bus standing in for RabbitMQ.
- Postgres (or H2) standing in for DynamoDB — `ExecutionState` checkpoint table.
- HITL flow: agent fills → screenshot → "halt" state persisted → human approves in a minimal React UI (or CLI) → worker resumes, re-checks state, clicks Submit.
- Double-submission guard per spec §6: check `ExecutionState` for `Transaction_ID` before every Submit click.

**Exit criteria:**
- A complete happy-path submit gated by human approval.
- Restart mid-flow never re-submits a completed transaction.

---

## M6 — Production AWS infra · planned

Maps 1:1 to [DeploymentPlan.md](DeploymentPlan.md) phases 1–6. Same interfaces, different implementations:

| Local substitute (M1–M5) | Production (M6) |
|---|---|
| Spring AMQP / event bus | RabbitMQ / Amazon MQ |
| Postgres | DynamoDB |
| Local filesystem audit bundle | S3 with Object Lock, 7y WORM |
| env vars / local secrets file | AWS Secrets Manager |
| JVM on laptop | GraalVM native image on Fargate (private subnet, NAT Gateway w/ static EIP) |
| Console logging | OpenTelemetry → AWS X-Ray |

---

## M7 — Exception-only autonomy · planned

Per spec Phase 4. Out of scope until M3–M6 have been proven in production on low-risk, small-batch payroll.

---

## Cross-cutting capabilities

Added progressively as milestones demand them — not upfront.

| Capability | Lands at | Spec ref |
|---|---|---|
| Strict selectors (fail-fast on UI change) | M2 (in use) | §5 |
| Password redaction in step log | M2 (in use) | §7.3 partial |
| Credential source-of-truth outside repo | M2.5 (in use) | §2 |
| HAR body-field scrubbing | M2.5 (in use) | §7 |
| Pre-commit secrets gate | M2.5 (in use) | — |
| HITL `pause` step + encrypted session reuse | M3.0 (in use) | §6 |
| Shadow-mode guard (`submit`/`shadowMode` flags) | M3.0 (in use) | Phase 1 |
| Payload SHA-256 hashing | M4 | §5 Ingestion |
| Java-native PII redactor (salary, SSN, etc.) | M4 | §7.3 |
| OpenTelemetry tracing | M4 | §7.1 |
| Idempotent MCP tooling | M4 | §4 |
| State checkpoint / double-submit guard | M5 | §6 |
| AWS Secrets Manager + customer-managed KMS | M6 | §2 |
| WORM audit storage | M6 | §7 |

---

## Explicitly deferred

- GraalVM native image — M6 only.
- CDK infra-aws module — M6 only.
- LangChain4j wiring — M4 (pointless before MCP exists).
- RabbitMQ / DynamoDB / S3 WORM — M5/M6 (local substitutes until then).
- Praxis BPM integration — Praxis-side scope tracked in [PraxisIntegrationHandoff.md](PraxisIntegrationHandoff.md); agent-worker readiness tracked in P1 above.

---

## How to keep this doc current

When a milestone ships:
1. Flip `·` to `✓` in the top table.
2. Add a **Delivered** block with commit SHAs and run-evidence paths.
3. Move the next milestone to `→ in progress` or `· NEXT`.

Planned-work sections are expected to change once real-portal constraints are known — treat them as a sketch, not a contract.
