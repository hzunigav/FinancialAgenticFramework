# Implementation Plan: Financial Agentic Framework

Living document. Source of truth for *where we are* vs. *what's next*.
Complements [DeploymentPlan.md](DeploymentPlan.md) (infra go-live) and [TechnicalRequirements.md](TechnicalRequirements.md) (the spec).

**Legend:** ✓ done · → in progress · · planned

---

## Where we are

| | Milestone | Status |
|---|---|---|
| M1 | Mock vertical slice | ✓ |
| M2 | Portal adapter + Read-Back verifier | ✓ |
| M2.5 | Credentials provider + HAR scrubbing | ✓ |
| M3.0 | Engine plumbing for MFA + session + shadow | ✓ |
| M3 | Real portal access (Shadow Mode, fill-only) | · NEXT — needs prod portal details |
| M4 | MCP Server + real payroll data feed | · |
| M5 | HITL gate + Safe-Submit loop | · |
| M6 | Production AWS infra | · |
| M7 | Exception-only autonomy | · |

---

## M1 — Mock vertical slice · ✓

**Goal:** Prove the full stack from browser login through audit artifacts, locally, on the user's known stack (Spring Boot + Java).

**Delivered:**
- Monorepo: `common-lib`, `contract-api`, `mcp-payroll-server`, `agent-gateway`, `agent-worker`, `testing-harness`, `infra-aws` (placeholder).
- Spring Boot mock portal with form login, CSRF, session cookies — [testing-harness/](testing-harness/).
- Playwright agent drives login end-to-end — [agent-worker/](agent-worker/).
- Per-run audit bundle: `trace.zip`, `network.har`, `report.png`, `manifest.json`.
- Tomcat access log from mock portal for independent server-side audit.

**Commits:** `5a0123c`, `65db252`.

---

## M2 — Portal adapter + Read-Back verifier · ✓

**Goal:** Externalize portal-specific logic so a new portal is a new YAML file, not new Java. Add deterministic verification per spec §7.2.

**Delivered:**
- `PortalDescriptor` YAML schema + loader — [PortalDescriptor.java](agent-worker/src/main/java/com/financialagent/worker/portal/PortalDescriptor.java), [PortalDescriptorLoader.java](agent-worker/src/main/java/com/financialagent/worker/portal/PortalDescriptorLoader.java).
- `PortalEngine` executes declarative steps (`navigate` / `fill` / `click` / `waitForUrl`) with `${credentials.*}` placeholder resolution and value redaction — [PortalEngine.java](agent-worker/src/main/java/com/financialagent/worker/portal/PortalEngine.java).
- `PortalScraper` extracts a name→text map using Playwright strict locators (spec §5) — [PortalScraper.java](agent-worker/src/main/java/com/financialagent/worker/portal/PortalScraper.java).
- `ReadBackVerifier<T extends Record>` — reflection-based field diff, portal-agnostic, reusable for every future domain record — [ReadBackVerifier.java](common-lib/src/main/java/com/financialagent/common/verify/ReadBackVerifier.java).
- First portal descriptor: [mock-portal.yaml](agent-worker/src/main/resources/portals/mock-portal.yaml).
- 5 unit tests covering MATCH / one-field-MISMATCH / all-fields-MISMATCH / type-mismatch / null-guard — [ReadBackVerifierTest.java](common-lib/src/test/java/com/financialagent/common/verify/ReadBackVerifierTest.java).
- Verification result recorded in [manifest.json](agent-worker/artifacts/).

**Run evidence:** `agent-worker/artifacts/20260422T135445-78418/manifest.json` — MATCH on `(username, reportDate)`.

---

## M2.5 — Credentials provider + HAR scrubbing · ✓

**Goal:** Gate the repo against real credentials before they arrive. The framework must be safe to hand a prod password on day one of M3.

**Delivered:**
- `CredentialsProvider` interface + `PortalCredentials` record in `common-lib` — [CredentialsProvider.java](common-lib/src/main/java/com/financialagent/common/credentials/CredentialsProvider.java), [PortalCredentials.java](common-lib/src/main/java/com/financialagent/common/credentials/PortalCredentials.java). Same interface will back the M6 `AwsSecretsManagerCredentialsProvider`.
- `LocalFileCredentialsProvider` — reads `~/.financeagent/secrets.properties` (override via `FINANCEAGENT_SECRETS_FILE`), refuses to load if the file grants read access to anyone but the owner (POSIX or ACL) — [LocalFileCredentialsProvider.java](common-lib/src/main/java/com/financialagent/common/credentials/LocalFileCredentialsProvider.java).
- Mock credentials removed from committed [config.properties](agent-worker/src/main/resources/config.properties). Template at repo root: [secrets.properties.example](secrets.properties.example).
- `securityContext.scrubHarFields` block in [PortalDescriptor.java](agent-worker/src/main/java/com/financialagent/worker/portal/PortalDescriptor.java) — declarative, per-portal list of URL patterns + body field names to redact in the HAR.
- [HarScrubber.java](agent-worker/src/main/java/com/financialagent/worker/portal/HarScrubber.java) — post-processes `network.har` after context close. Handles `application/x-www-form-urlencoded` (both the `params` array and the raw `text`) and `application/json` (recursive). Falls back to stripping `postData` entirely for unknown content types — fail-closed.
- Repo pre-commit hook at [.githooks/pre-commit](.githooks/pre-commit) — blocks any commit introducing a `portals.<id>.credentials.*=*` line for anything other than `mock-portal`, and blocks any file named `secrets.properties`. Activate once per clone: `git config core.hooksPath .githooks`.

**Run evidence:** `agent-worker/artifacts/20260422T142602-e04f2/network.har` — login POST body contains `password=%5BREDACTED%5D` (the URL-encoded form of `[REDACTED]`), and `grep "password=password" network.har manifest.json` returns nothing.

---

## M3.0 — Engine plumbing for MFA + session + shadow · ✓

**Goal:** Land the portal-agnostic mechanisms M3 needs (pause for SMS/email MFA, encrypted session reuse, shadow-mode guard) ahead of real-portal access, so the portal-specific work in M3 is just a descriptor + domain record.

**Delivered:**
- `pause` action in `PortalEngine` — halts, prompts the operator (stdin in dev), injects the response into bindings under a `bindTo` key, audits the prompt but never the response — [PortalEngine.java](agent-worker/src/main/java/com/financialagent/worker/portal/PortalEngine.java).
- Shadow-mode guard — top-level `shadowMode: true` in descriptor + per-step `submit: true` flag; guard throws `PortalEngine.ShadowHalt` before touching the page, caller marks the run `SHADOW_HALT` rather than `FAILED` or `SUCCESS`. Unit-tested — [PortalEngineShadowModeTest.java](agent-worker/src/test/java/com/financialagent/worker/portal/PortalEngineShadowModeTest.java).
- `SessionStore` interface + `LocalEncryptedSessionStore` in `common-lib` — AES-256-GCM, per-portal `.enc` blob at `~/.financeagent/sessions/<portalId>.enc`, key at `~/.financeagent/session-key`. TTL declared per-portal via `session.ttlMinutes`. 8 unit tests: round-trip, no-plaintext-on-disk, TTL expiry purges, missing-file, purge, per-portal isolation, key reuse, IV randomness — [LocalEncryptedSessionStoreTest.java](common-lib/src/test/java/com/financialagent/common/session/LocalEncryptedSessionStoreTest.java).
- Descriptor restructured from a flat `steps` list into `authSteps` (pre-auth; skipped when a valid session is loaded) and `steps` (post-auth; always run) — [PortalDescriptor.java](agent-worker/src/main/java/com/financialagent/worker/portal/PortalDescriptor.java).
- Windows ACL check relaxed to ignore privileged system principals (SYSTEM, Administrators, LOCAL SERVICE, NETWORK SERVICE) — they're granted access to everything in a user profile by default and excluding them fought the platform rather than the threat model.
- Manifest records `portal.shadowMode` and `portal.sessionReused` so auditors can tell at a glance whether a run went through login or reused a session — [RunManifest.java](agent-worker/src/main/java/com/financialagent/worker/RunManifest.java).

**Run evidence:** consecutive runs `20260422T145923-c66c1` (Session reused: false, authSteps ran) and `20260422T145929-d6309` (Session reused: true, first manifest step is `auth-skipped | session-reused`, scrape+verify still MATCH). HAR remains scrubbed across both runs.

---

## M3 — Real portal access (Shadow Mode) · NEXT

**Prereq:** Portal URL, login page structure, MFA delivery method (already confirmed: SMS / email link), and a description of the data to read back.

**Goal:** Exercise the framework against a real site with zero write-risk. Spec Phase 1.

**Planned work (what still remains — the engine is ready):**
- Replace placeholder `ReportSnapshot` with real `PayrollData` record in `common-lib` (BigDecimal amounts, employee fields, custom serializers per spec §3).
- New descriptor: `agent-worker/src/main/resources/portals/<real-portal>.yaml`. Expected shape: `authSteps` (navigate → fill → click → pause for MFA code → fill → click → waitForUrl), `steps` (navigate to payroll page, any selects), `scrape` (field-to-selector map), `securityContext.scrubHarFields` updated with the real portal's login and MFA request shapes.
- `PayrollMapper` to translate scraped strings into `PayrollData`.
- Extend `PortalEngine` action set as the real portal demands (e.g., `select`, `press`, `waitForSelector`, `uploadFile`) — only the ones actually needed.
- Add session-validity probe step for the real portal, since server-side sessions can die before client TTL — a cheap `navigate` + expected-URL check at the start of `steps`; on mismatch, purge session and force authSteps to run.

**Exit criteria:**
- Agent logs into the real portal once with a human-provided MFA code, fills the form, halts before submit via the shadow-mode guard.
- Subsequent runs within the session TTL skip login entirely.
- Read-Back compares scraped-after-fill against the source payload — MATCH required to call the run successful.
- Full audit bundle captured. HAR scrubbing confirmed against the real portal's login and MFA payload shapes.

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
- Praxis BPM integration — M5 (HITL can be demonstrated without Praxis first).

---

## How to keep this doc current

When a milestone ships:
1. Flip `·` to `✓` in the top table.
2. Add a **Delivered** block with commit SHAs and run-evidence paths.
3. Move the next milestone to `→ in progress` or `· NEXT`.

Planned-work sections are expected to change once real-portal constraints are known — treat them as a sketch, not a contract.
