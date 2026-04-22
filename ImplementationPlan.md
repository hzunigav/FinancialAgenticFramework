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
| M3 | Real portal access (Shadow Mode, fill-only) | · NEXT — needs prod credentials |
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

## M3 — Real portal access (Shadow Mode) · NEXT

**Prereq:** User grants credentials and access to a production payroll portal.

**Goal:** Exercise the framework against a real site with zero write-risk. Spec Phase 1.

**Planned work:**
- Replace placeholder `ReportSnapshot` with real `PayrollData` record in `common-lib` (BigDecimal amounts, employee fields, custom serializers per spec §3).
- New descriptor: `agent-worker/src/main/resources/portals/<real-portal>.yaml`.
- Extend `PortalEngine` action set as the real portal demands (e.g., `select`, `press`, `waitForSelector`, `uploadFile`).
- Add Shadow-Mode guard: engine refuses to execute any step marked `submit: true` when descriptor has `shadowMode: true`. Logs what *would* have been submitted.
- Move credentials out of `config.properties` into environment variables / a git-ignored local secrets file. AWS Secrets Manager swap-in is deferred to M6.

**Exit criteria:**
- Agent logs into the real portal, fills the form, halts before submit.
- Read-Back compares scraped-after-fill against the source payload — MATCH required to call the run successful.
- Full audit bundle captured.

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
| Password redaction in logs | M2 (in use) | §7.3 partial |
| Payload SHA-256 hashing | M4 | §5 Ingestion |
| Java-native PII redactor (salary, SSN, etc.) | M4 | §7.3 |
| OpenTelemetry tracing | M4 | §7.1 |
| Idempotent MCP tooling | M4 | §4 |
| State checkpoint / double-submit guard | M5 | §6 |
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
