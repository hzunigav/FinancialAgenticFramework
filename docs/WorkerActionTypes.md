# Worker Action Types

Every task the agent-worker performs belongs to one of three categories. The category determines which base class to use, which descriptor YAML to write, which envelope to emit, and which queue to listen on. Getting this wrong at design time is the single most common source of runtime failures — typically a misleading `IllegalStateException` that looks like a missing parameter but is actually the wrong adapter for the flow.

**Audience:** developers adding a new portal or action, and the AI assistant helping them. Read this before touching `PortalRunService.newAdapter()` or writing a descriptor.

---

## The three categories

| Category | Intent | Portal interaction | Position in cycle | Primary example |
|---|---|---|---|---|
| **Input** | Read the current state from a source portal; produce a data envelope | Read-only scrape | First | `mock-payroll-capture`, `autoplanilla` |
| **Analysis** | Transform, validate, gate, or enrich data without portal interaction | None | Middle | HITL review, `EmployeeMatcher`, roster-diff aggregation |
| **Output** | Write the approved data to a target portal; produce a confirmation envelope | Write (fill → submit → confirm) | Last | `mock-payroll`, `ccss-sicere`, `ins-rt-virtual` |

A single monthly payroll cycle uses all three in sequence: **Input** (capture from AutoPlanilla) → **Analysis** (HITL review) → **Output** × 3 (submit to CCSS, INS, Hacienda in parallel). See [PayrollOrchestrationFlow.md](PayrollOrchestrationFlow.md) for the BPMN model.

---

## Input actions (Capture)

### Intent

Scrape the current payroll state from a source portal. The result is the authoritative roster that feeds all downstream analysis and submissions. No data is written to the portal.

### Required components

| Component | Specification |
|---|---|
| **Adapter class** | Extends `AbstractCaptureAdapter`; implements `buildCaptureOutcome()`. `beforeSteps()` is a no-op (inherited from `PortalAdapter`) — no pre-population needed since there is nothing to write. |
| **Descriptor YAML** | Named `<portalId>-capture.yaml`. Contains: `authSteps` (login), `steps` (navigate to data page + `waitForSelector`), `scrape.rows` (per-employee columns). **Must NOT contain fill, click-submit, or scrape of confirmation totals** — those belong in Output. |
| **Registry entry** | `PortalRunService.newAdapter(portalId, isCapture=true)` |
| **Queue** | `financeagent.tasks.capture.<portalId>` |
| **Listener** | `PayrollCaptureListener` |
| **Envelope emitted** | `payroll-capture-result.v1.json` → `CaptureResultBody(status, totals, employees[])` |
| **Audit artifacts** | `manifest.json`, `payroll-capture-result.v1.json` (AES-256-GCM encrypted), `network.har` (scrubbed), `trace.zip`, `report.png` |

### Envelope shape

```
PayrollCaptureResult
  envelope:   EnvelopeMeta  (envelopeId, businessKey, firmId, issuerRunId)
  task:        CaptureTask  (sourcePortal, period, planilla)
  encryption:  Encryption   (algorithm, kekRef, iv, tag)
  result:      String        ← AES-GCM ciphertext of CaptureResultBody
  audit:        Audit        (payloadSha256)
```

Cleartext body (`CaptureResultBody`):
```
status:     SUCCESS | PARTIAL | FAILED
totals:     { currency, grossSalaries, renta, employeeCount }
employees:  [ { id, displayName, grossSalary, attributes{} } ... ]
```

### What `buildCaptureOutcome()` must return

```java
new CaptureOutcome(
    status,          // typically "CAPTURED"
    body,            // CaptureResultBody with employees list
    sourcePortalId,  // e.g. "autoplanilla" or "mock-payroll"
    businessKey,     // e.g. "2026-01::planillaName", from bindings
    issuer,          // e.g. "agent-worker/autoplanilla"
    period,          // Period(from, to) — null if not meaningful for this portal
    planilla         // Planilla(id, name) — null if not meaningful
);
```

### Reference implementation

- Adapter: [MockPayrollCaptureAdapter.java](../agent-worker/src/main/java/com/neoproc/financialagent/worker/MockPayrollCaptureAdapter.java)
- Descriptor: [portals/mock-payroll-capture.yaml](../agent-worker/src/main/resources/portals/mock-payroll-capture.yaml)
- Production example: [AutoplanillaAdapter.java](../agent-worker/src/main/java/com/neoproc/financialagent/worker/AutoplanillaAdapter.java)

---

## Analysis actions

### Intent

Transform, validate, enrich, or gate data without any portal interaction. No browser, no queue message, no envelope emitted directly. Analysis actions are driven by the BPM process (Flowable service tasks or user tasks), not by RabbitMQ listeners.

### Sub-types

| Sub-type | Driven by | Human required | Implementation |
|---|---|---|---|
| **Automated** | Flowable service task (JavaDelegate) | No | Java class called by the BPMN engine |
| **HITL** | Flowable user task | Yes | Praxis task form; default Flowable view in v1 |
| **LLM-assisted** | Flowable service task | No (or as escalation) | LangChain4j agent — deferred post-v1 |

### Required components

| Component | Specification |
|---|---|
| **No adapter class** | Analysis logic lives in Flowable JavaDelegates, utility classes (e.g., `EmployeeMatcher`), or in Praxis service code — not in `PortalAdapter` subclasses. |
| **No descriptor YAML** | No browser interaction. |
| **No registry entry** | `PortalRunService` is not involved. |
| **No queue** | BPM engine calls the service task directly on its execution thread. |
| **Audit** | Flowable task history + Praxis audit log. The `envelopeId` from the upstream Input action is the correlation key throughout. |

### Current analysis actions in the payroll cycle

| Name | Sub-type | When it fires | What it does |
|---|---|---|---|
| Capture review | HITL | After every capture | Administrator reviews captured employee list and totals; approves or rejects. |
| Roster-diff aggregation | Automated | After parallel submit join | Merges three `rosterDiff` results; computes aggregate status (worst of three). |
| Lifecycle confirmation | HITL | When `missingFromPortal` or `missingFromPayroll` non-empty | HR/payroll confirms hire or termination before any registration action. |
| Integrity review | HITL | On aggregate status `MISMATCH` | Senior payroll reviews total divergence before deciding to resubmit or escalate. |

---

## Output actions (Submit)

### Intent

Write the approved payroll data to a target portal (fill form fields, submit, read back the confirmation). The input is a `PayrollSubmitRequest` envelope from the BPM process containing the canonical roster; the output is a `PayrollSubmitResult` envelope with submission totals and roster diff.

### Required components

| Component | Specification |
|---|---|
| **Adapter class** | Extends `AbstractSubmitAdapter`; implements `buildSubmitOutcome()`. `beforeSteps()` **must** be overridden: scrape displayed roster, load canonical employees from `params.source.submitRequest` (written by `PortalRunService.run()` before `beforeSteps()` fires), fuzzy-match them, and populate `params.employeeUpdates` list binding for the descriptor's `forEach` action. |
| **Descriptor YAML** | Named `<portalId>.yaml`. Contains: `authSteps`, `steps` (navigate + `forEach` over `params.employeeUpdates` filling salary inputs + `click submit` + wait for confirmation), `scrape.fields` (grandTotal, submittedTotal, updatedCount). **Must NOT contain row-scraping of employee data** — that belongs in Input. |
| **Registry entry** | `PortalRunService.newAdapter(portalId, isCapture=false)` |
| **Queue** | `financeagent.tasks.submit.<portalId>` |
| **Listener** | Submit listener (e.g., `PayrollTaskListener`) |
| **Envelope consumed** | `payroll-submit-request.v1` → `SubmitRequestBody(employees[])` |
| **Envelope emitted** | `payroll-submit-result.v1.json` → `SubmitResultBody(status, totals, submittedRows[], rosterDiff, review?)` |
| **Audit artifacts** | Same as Input, plus confirmation ID from portal (carried in `SubmitResultBody`) |

### Envelope shape

```
PayrollSubmitResult
  envelope:   EnvelopeMeta  (envelopeId, businessKey, firmId, issuerRunId)
  task:        SubmitTask    (targetPortal, sourceCaptureEnvelopeId, operation)
  encryption:  Encryption
  result:      String         ← AES-GCM ciphertext of SubmitResultBody
  audit:        Audit
```

Cleartext body (`SubmitResultBody`):
```
status:          SUCCESS | PARTIAL | MISMATCH | FAILED
confirmationId:  String   ← portal-issued submission ID (CCSS planilla ID, etc.)
totals:          { currency, grandTotal, updatedCount }
submittedRows:   [ { id, displayName, salary } ... ]
rosterDiff:      { missingFromPortal[], missingFromPayroll[] }
review:          { status, summary, signals[], actions[] }  ← present when PARTIAL or MISMATCH
```

### Status routing

| Status | Condition | BPM action |
|---|---|---|
| `SUCCESS` | `canonicalGrandTotal == serverGrandTotal` AND `rosterDiff` empty | Proceed |
| `PARTIAL` | Totals match but `rosterDiff` non-empty | Lifecycle subprocess (register hires / deregister terminations) |
| `MISMATCH` | `canonicalGrandTotal != serverGrandTotal` | HITL integrity review |
| `FAILED` | Submission did not complete (exception, portal error) | Engineering escalation |

### Reference implementation

- Adapter: [MockPayrollAdapter.java](../agent-worker/src/main/java/com/neoproc/financialagent/worker/MockPayrollAdapter.java)
- Descriptor: [portals/mock-payroll.yaml](../agent-worker/src/main/resources/portals/mock-payroll.yaml)

---

## When a portal handles both Input and Output

A single `PORTAL_ID` worker can handle both the capture queue and the submit queue. In this case, `PortalRunService.newAdapter()` must register **two separate adapter instances** under the same `portalId`, distinguished by the `isCapture` flag. Two descriptor YAMLs are also required — one per flow.

```java
// PortalRunService.newAdapter()
case "mock-payroll" -> isCapture
    ? new MockPayrollCaptureAdapter()   // Input: reads /employees
    : new MockPayrollAdapter();          // Output: fills/submits salaries
```

**This is the most common configuration mistake.** If only the Output adapter is registered, the capture listener will receive the capture message, load the Output adapter, call `beforeSteps()`, find no canonical employee source in the bindings, and throw `IllegalStateException` — which looks like a missing parameter but is actually the wrong adapter. The fix is always: register the Input adapter for `isCapture=true`.

---

## Adding a new action — checklist

Work through this before writing any code. Each question maps to a required deliverable.

**Step 1 — Identify the category**
- [ ] Is this a **read** from a portal? → **Input**
- [ ] Is this a **write** to a portal? → **Output**
- [ ] Is this a transformation, validation, or human gate with no portal? → **Analysis**

**Step 2 — For Input and Output: does this portal already have the other flow registered?**
- [ ] Check `PortalRunService.newAdapter()` for an existing entry under this `portalId`
- [ ] If an entry exists, does it cover the other flow (`isCapture` opposite of yours)?
- [ ] If so, you are adding the second adapter for a full-cycle portal — both will coexist under the same `portalId` key

**Step 3 — Create the descriptor YAML** (Input or Output only)
- [ ] Input: `agent-worker/src/main/resources/portals/<portalId>-capture.yaml`
  - auth steps → navigate to data page → `waitForSelector` → row `scrape`
  - No fill steps, no submit, no totals scrape
- [ ] Output: `agent-worker/src/main/resources/portals/<portalId>.yaml`
  - auth steps → `forEach` salary fills → `click submit` → wait for confirmation → totals `scrape`
  - No row scrape of employee list

**Step 4 — Create the adapter class** (Input or Output only)
- [ ] Input: `<PortalId>CaptureAdapter extends AbstractCaptureAdapter`
  - Implement `buildCaptureOutcome()` — map `scrapedRows` to `EmployeeRow` list, compute totals, return `CaptureOutcome`
  - `beforeSteps()` typically not needed (inherited no-op)
- [ ] Output: `<PortalId>Adapter extends AbstractSubmitAdapter`
  - Override `beforeSteps()` — navigate, scrape displayed roster, load canonical from `params.source.submitRequest`, match, populate `params.employeeUpdates`
  - Implement `buildSubmitOutcome()` — reconcile totals, build `SubmitResultBody`, return `SubmitOutcome`

**Step 5 — Register in `PortalRunService.newAdapter()`**
- [ ] Add a `case "<portalId>"` entry (or add to existing entry)
- [ ] Input: `isCapture=true` branch → your new `CaptureAdapter`
- [ ] Output: `isCapture=false` branch → your new submit `Adapter`

**Step 6 — For Analysis: no adapter, no descriptor, no registry entry**
- [ ] Automated: write a Flowable `JavaDelegate` and wire it as a service task in the BPMN
- [ ] HITL: define the user task in the BPMN; Praxis renders the default Flowable task form in v1

**Step 7 — Verify the contract end-to-end**
- [ ] Input: `PayrollCaptureListener` reads `outcome.runDir().resolve("payroll-capture-result.v1.json")` — confirm the adapter writes it under that exact name (handled by `AbstractCaptureAdapter.emitCaptureEnvelope()`)
- [ ] Output: submit listener passes the `PayrollSubmitRequest` to `PortalRunService.run()` as the second argument — confirm `PortalRunService.run()` writes it to `params.source.submitRequest` before calling `beforeSteps()`

**Step 8 — Add fixture coverage**
- [ ] Capture a DOM fixture with `-Dfixture.capture=true` on one real (or mock) run
- [ ] Promote to `agent-worker/src/test/resources/fixtures/<portalId>/post-steps.html`
- [ ] Write a `DescriptorFixtureTest` that asserts the expected scrape output — catches selector regressions in CI without hitting the real portal

**Step 9 — Smoke test**
- [ ] Start agent-worker with `PORTAL_ID=<portalId>`
- [ ] Trigger a cycle from Praxis
- [ ] Verify `/tmp/agent-worker.log` shows: `run started portal=<portalId>-capture` (for Input) or `run started portal=<portalId>` (for Output)
- [ ] Verify envelope written and result published to `financeagent.results`
- [ ] Verify workflow instance in Praxis advances past the service task

---

## Common failure modes and diagnosis

| Symptom | Root cause | Fix |
|---|---|---|
| `IllegalStateException: requires either -Dsource.captureEnvelope, -Dsource.submitRequest, or params.salary.*` | Output adapter loaded for a capture run — `PortalRunService.newAdapter()` is missing the `isCapture=true` branch | Register a separate Input adapter for `isCapture=true` |
| `IllegalArgumentException: Portal descriptor not found: /portals/<portalId>-capture.yaml` | Input adapter registered but `-capture.yaml` descriptor not created | Create `<portalId>-capture.yaml` |
| `CaptureResultBody.employees` is empty on HITL task | Capture descriptor's `scrape.rows` selector doesn't match the portal's actual DOM | Update `scrape.rows.selector` and columns; re-run fixture capture |
| `net::ERR_CONNECTION_REFUSED` during Playwright | Target service (testing-harness, real portal) not reachable from the worker | Confirm service is running; confirm `baseUrl` in descriptor matches actual port |
| Submit runs but `status=MISMATCH` on every run | `canonicalGrandTotal` (from submit request) does not match `serverGrandTotal` (from portal after submit) | Check employee matching in `beforeSteps()` — mismatched IDs produce `missingFromPortal` entries that shift the grand total |
| Envelope published but Praxis workflow does not advance | `businessKey` on the result envelope does not match the Flowable correlation key Praxis expects | Check how `businessKey` is constructed in `buildCaptureOutcome()` / `buildSubmitOutcome()` against what Praxis's Receive Task uses for correlation |

---

## Praxis integration reference

This section describes each category from Praxis's perspective — what to publish, what to expect back, how to wire the BPMN, and how to handle each status. It assumes the RabbitMQ topology from [PraxisIntegrationHandoff.md §C.1](PraxisIntegrationHandoff.md#c1-broker--topology) and the encryption contract from [CONTRACT.md §4](../contract-api/CONTRACT.md#4-encryption) are already understood.

---

### How categories map to BPM constructs

| Category | Praxis construct | Queue direction | Praxis role |
|---|---|---|---|
| **Input** | Service Task (fire-and-forget publish) → Intermediate Catch Event or Receive Task | Praxis → worker → Praxis | Build and publish `PayrollCaptureRequest`; wait for `PayrollCaptureResult`; route on `result.status` |
| **Analysis (automated)** | Service Task (`JavaDelegate`, no queue) | No queue | Implement transformation in-process; no envelope interaction |
| **Analysis (HITL)** | User Task | No queue | Surface `review` payload to reviewer; route process on reviewer's action |
| **Output** | Service Task (fire-and-forget publish) → Receive Task | Praxis → worker → Praxis | Build `PayrollSubmitRequest` from approved capture body; wait for `PayrollSubmitResult`; route on `result.status` |

The three-step sequence in the payroll cycle is: one **Input** Service Task → one **Analysis (HITL)** User Task → N parallel **Output** Service Tasks (one per target portal).

---

### Input (Capture) — Praxis side

#### What to publish

Publish a `PayrollCaptureRequest` to exchange `financeagent.tasks`, routing key `capture.<portalId>`.

```json
{
  "$schema": "payroll-capture-request.v1",
  "envelope": {
    "envelopeId":   "<UUID — generated by Praxis>",
    "businessKey":  "2026-01::Planilla Regular",
    "firmId":       1351,
    "locale":       "es",
    "createdAt":    "2026-04-27T15:35:48Z",
    "issuer":       "praxis-bpm/payroll-process",
    "issuerRunId":  "<flowable-process-instance-id>"
  },
  "task": {
    "sourcePortal": "autoplanilla",
    "period":   { "from": "2026-01-01", "to": "2026-01-31" },
    "planilla": { "id": null, "name": "Planilla Regular" }
  }
}
```

Required message properties:
- `content-type: application/json`
- `message-id: <envelope.envelopeId>`
- `correlation-id: <envelope.businessKey>`
- `type: payroll-capture-request.v1`

**`businessKey` is the cycle's stable correlation key.** Set it once here; carry it unchanged on every downstream envelope (submit requests, submit results). Flowable Receive Tasks correlate on this value. Format: `<YYYY-MM>::<planillaName>` — unique per firm per period.

#### What you receive back

The worker publishes `PayrollCaptureResult` to exchange `financeagent.results` with `correlation-id = businessKey`.

```json
{
  "$schema": "payroll-capture-result.v1",
  "envelope": {
    "envelopeId":  "<new UUID — worker-generated>",
    "businessKey": "2026-01::Planilla Regular",
    "firmId":      1351,
    "issuerRunId": "<worker run-id>"
  },
  "task":       { "sourcePortal": "autoplanilla", ... },
  "encryption": { "scheme": "aws-kms-envelope-v1", "keyName": "alias/payroll-firm-1351", ... },
  "result":     "<ciphertext>",
  "audit":      { "payloadSha256": "<sha256>" }
}
```

Decrypt `result` with `KmsEnvelopeClient.decrypt(firmId, ciphertext)` to get:

```json
{
  "status":    "SUCCESS",
  "totals":    { "currency": "CRC", "grossSalaries": 17239260.72, "renta": 1583210.00, "employeeCount": 12 },
  "employees": [
    { "id": "117040400", "displayName": "ANDRES MARIN SANCHEZ", "grossSalary": 902403.59, "attributes": {} },
    ...
  ]
}
```

#### Status routing after capture

| `result.status` | Meaning | BPM action |
|---|---|---|
| `CAPTURED` (normal) | Workers emits this when capture succeeds | Advance to HITL review User Task |
| `FAILED` | Portal unreachable, selector broke, credentials rejected | Route directly to engineering-escalation User Task — do not proceed to submit |
| `DUPLICATE_ENVELOPE` | Worker already processed this `envelopeId` | Idempotency duplicate — re-send with a new `envelopeId` if genuinely a retry |

> **Never advance to submit when capture status is `FAILED`.** There is no capture body to forward, and the submit adapter requires one.

#### Receive Task timeout

Set a boundary timer event on the capture Receive Task — 10 minutes is a safe upper bound for all currently targeted portals. On timeout, route to engineering escalation. The worker will eventually publish a result even for slow runs; the timer is a safety net for silent failures (broker down, pod evicted).

---

### Analysis — Praxis side

#### Automated service tasks

Standard Flowable `JavaDelegate` implementation. No queue interaction. Common operations:

| Operation | What it does | Where it sits in the cycle |
|---|---|---|
| Build submit request | Decrypt capture body; construct `PayrollSubmitRequest` per portal | Between capture Receive Task and submit fan-out |
| Aggregate results | Merge N `PayrollSubmitResult` envelopes; compute worst-case `status` and union `rosterDiff` | After parallel submit join gateway |

See [§ Building a submit request from a capture result](#building-a-submit-request-from-a-capture-result) for the build pattern.

#### HITL User Tasks

The capture review gate is the primary HITL action in v1. It receives the decrypted `CaptureResultBody` as task data and surfaces it for human review before any portal submission occurs.

**Task input (what the form renderer shows):**

```
Capture summary
  Period:       2026-01-01 → 2026-01-31
  Planilla:     Planilla Regular
  Employees:    12
  Gross total:  CRC 17,239,260.72
  Renta total:  CRC 1,583,210.00

Employee list (collapsed table by default):
  ID            Name                          Gross salary
  117040400     ANDRES MARIN SANCHEZ          902,403.59
  ...
```

**Reviewer actions and routing:**

| Action | Signal | BPM routing |
|---|---|---|
| **APPROVED** | Human confirms data is correct | Advance to parallel submit fan-out |
| **REJECTED** | Human flags a data error at the source | Loop back to a "Fix at AutoPlanilla" user task; re-run capture after fix |

For `MISMATCH` / `PARTIAL` results that arrive *after* a submit (the integrity review gate), the `SubmitResultBody.review` block provides structured input:

```json
"review": {
  "severity":       "MISMATCH",
  "summary":        "Portal grand total exceeds canonical by 1,319,818.62 CRC",
  "signals": [
    { "type": "TOTAL_GAP",           "canonical": 17239260.72, "portal": 18559079.34, "gap": 1319818.62 },
    { "type": "MISSING_FROM_PORTAL", "id": "117040400", "name": "ANDRES MARIN", "expectedSalary": 902403.59 }
  ],
  "allowedActions": ["RESUBMIT", "ACKNOWLEDGE", "ESCALATE"]
}
```

Render `summary` at the top, list `signals` grouped by `type`, expose exactly the buttons in `allowedActions`. Route the process based on the reviewer's choice:

| Reviewer action | BPM routing |
|---|---|
| `RESUBMIT` | Loop back to the relevant submit Service Task with a new `envelopeId`; preserve `task.sourceCaptureEnvelopeId` for chain-of-custody |
| `ACKNOWLEDGE` | Complete with `PARTIAL_ACCEPTED`; record reviewer comment in artifact store; advance cycle |
| `ESCALATE` | Route to engineering-escalation user task outside the payroll flow |

---

### Output (Submit) — Praxis side

#### Building a submit request from a capture result

```java
// Inside the "Build submit request" JavaDelegate
PayrollCaptureResult capture = /* from process variable set by capture Receive Task */;
CaptureResultBody captureBody = kmsEnvelopeClient.decrypt(capture.envelope().firmId(),
                                                           capture.result());

SubmitRequestBody submitBody = new SubmitRequestBody(
    captureBody.employees());                          // forward approved roster unchanged

String encryptedSubmitBody = kmsEnvelopeClient.encrypt(
    capture.envelope().firmId(), submitBody);

PayrollSubmitRequest submitRequest = new PayrollSubmitRequest(
    PayrollSubmitRequest.SCHEMA,
    new EnvelopeMeta(
        UUID.randomUUID().toString(),                  // new envelopeId per target portal
        capture.envelope().businessKey(),              // carry businessKey unchanged
        capture.envelope().firmId(),
        capture.envelope().locale(),
        Instant.now(),
        "praxis-bpm/payroll-process",
        flowableExecution.getProcessInstanceId()),
    SubmitTask.forSalaries(
        "ccss-sicere",                                 // or ins-rt-virtual / hacienda-ovi
        capture.envelope().envelopeId(),               // chain-of-custody link
        capture.task().period(),
        capture.task().planilla(),
        firmPortalIdentifierRepo.find(firmId, "ccss-sicere")),  // clientIdentifier if shared-creds
    encryptedSubmitBody,
    new Audit(null, null, null, sha256Of(submitBody)));
```

Key rules:
- Generate a **new** `envelopeId` per submit request (each target portal is an independent operation).
- Carry `businessKey` unchanged from the capture envelope — this is the Receive Task correlation key.
- Set `task.sourceCaptureEnvelopeId` to the capture result's `envelopeId` — audit chain-of-custody.
- For shared-creds portals (`autoplanilla`, `ins-rt-virtual`, `hacienda-ovi`), include `task.clientIdentifier` from the firm's `FirmPortalIdentifier` entity. Workers reject shared-portal envelopes that lack it.

#### What to publish

Publish to exchange `financeagent.tasks`, routing key `submit.<portalId>`.

Same message properties as capture: `content-type: application/json`, `message-id: <envelopeId>`, `correlation-id: <businessKey>`, `type: payroll-submit-request.v1`.

#### What you receive back

The worker publishes `PayrollSubmitResult` to `financeagent.results`. Decrypt `result` to get `SubmitResultBody`:

```json
{
  "status":        "SUCCESS",
  "confirmationId": "CCSS-2026-01-98765",
  "totals":        { "currency": "CRC", "grandTotal": 17239260.72, "updatedCount": 12 },
  "submittedRows": [ ... ],
  "rosterDiff":    { "missingFromPortal": [], "missingFromPayroll": [] },
  "review":        null
}
```

#### Status-to-BPMN routing

Route exclusively on `SubmitResultBody.status` after the parallel join:

| Status | Condition | BPMN action |
|---|---|---|
| `SUCCESS` | Totals match + `rosterDiff` empty | Mark portal complete; advance cycle |
| `PARTIAL` | Totals match but `rosterDiff` non-empty | Inclusive gateway → lifecycle subprocesses (register hires / deregister terminations) |
| `MISMATCH` | `canonicalGrandTotal ≠ serverGrandTotal` | User Task: integrity review — surface `result.review` payload |
| `FAILED` | Submit did not complete | User Task: engineering escalation — include `result.errorDetail` if present |

**Aggregation rule when multiple targets run in parallel:** aggregate status is the worst of the set: `FAILED > MISMATCH > PARTIAL > SUCCESS`. One `FAILED` target routes the whole join to engineering escalation; two `SUCCESS` + one `PARTIAL` routes to the lifecycle subprocess.

#### Receive Task timeout

Same 10-minute boundary timer as capture. Submit runs can be slower than capture (they write to the portal, wait for confirmation, scrape the result page). If the portal is slow, the worker will still publish — the timer exists for silent failures only.

---

### businessKey rules

`businessKey` is the single correlation thread across all envelopes in one payroll cycle. Treat it as a contract:

| Rule | Rationale |
|---|---|
| Praxis generates it **once** on the capture request | The worker carries it unchanged from request to result; downstream Receive Tasks must not re-generate it |
| Format: `<YYYY-MM>::<planillaName>` e.g. `2026-01::Planilla Regular` | Unique per firm per period; human-readable in Flowable history |
| The same `businessKey` appears on the capture result, every submit request, and every submit result for the same cycle | Flowable uses it to correlate each result to the right process instance |
| A **retry** (new envelope after `FAILED`) gets a **new `envelopeId`** but the **same `businessKey`** | `envelopeId` is the idempotency key (worker de-dupes by it); `businessKey` is the correlation key (Flowable uses it to find the right execution) |
| Praxis must not reuse the same `businessKey` for two concurrent cycles of the same firm + period | If a re-run is needed after a fully failed cycle, append a suffix to distinguish, e.g. `2026-01::Planilla Regular::retry-1` |

---

### Envelope field checklist for Praxis

Before publishing any message, verify these fields are populated:

**Capture request (`PayrollCaptureRequest`):**
- [ ] `envelope.envelopeId` — UUID v4, fresh per message
- [ ] `envelope.businessKey` — stable for this cycle
- [ ] `envelope.firmId` — long, required for KMS key resolution and credential scoping
- [ ] `envelope.issuerRunId` — Flowable process instance id
- [ ] `task.sourcePortal` — portalId of the capturing worker (e.g. `autoplanilla`)
- [ ] `task.period.from` / `task.period.to` — ISO-8601 dates for the payroll period
- [ ] `task.planilla.name` — identifies which planilla within the period

**Submit request (`PayrollSubmitRequest`):**
- [ ] All envelope fields above (new `envelopeId`, same `businessKey`, same `firmId`)
- [ ] `task.targetPortal` — portalId of the submit worker (e.g. `ccss-sicere`)
- [ ] `task.sourceCaptureEnvelopeId` — audit link to the capture result
- [ ] `task.clientIdentifier` — **required for shared-creds portals** (`ins-rt-virtual`, `hacienda-ovi`, `autoplanilla`); omit for per-firm portals (`ccss-sicere`)
- [ ] `encryption.*` — populated by `KmsEnvelopeClient.encrypt()`; `null` in dev with `local-aes-gcm-v1`
- [ ] `request` — encrypted `SubmitRequestBody` containing the approved `employees[]` list
- [ ] `audit.payloadSha256` — SHA-256 of the cleartext `SubmitRequestBody` before encryption

---

### Cross-references for Praxis

| Need | Where to look |
|---|---|
| RabbitMQ exchange/queue topology, message TTL, DLQ | [PraxisIntegrationHandoff.md §C.1](PraxisIntegrationHandoff.md#c1-broker--topology) |
| KMS key provisioning, encryption/decryption runbook | [CONTRACT.md §4](../contract-api/CONTRACT.md#4-encryption) + [PraxisIntegrationHandoff.md §A](PraxisIntegrationHandoff.md#2-workstream-a--aws-kms-envelope-encryption) |
| Secrets Manager layout, per-firm vs shared credentials | [PraxisIntegrationHandoff.md §B](PraxisIntegrationHandoff.md#3-workstream-b--aws-secrets-manager-for-portal-credentials) |
| Java DTOs (`PayrollCaptureResult`, `PayrollSubmitRequest`, etc.) | [CONTRACT.md §5](../contract-api/CONTRACT.md#5-java-dtos) — pull `contract-api` jar as Maven dependency |
| Full BPMN process model with gateway semantics | [PayrollOrchestrationFlow.md](PayrollOrchestrationFlow.md) |
| Per-portal timing, idempotency, and special fields (CCSS/INS/Hacienda) | [PayrollOrchestrationFlow.md §6](PayrollOrchestrationFlow.md#6-per-system-specifics) |
| HITL review UI requirements (v1 vs structured) | [PraxisIntegrationHandoff.md §G](PraxisIntegrationHandoff.md#8-workstream-g--human-in-the-loop-hitl-review) |
| Supervisor dashboard, canary, alerting | [PraxisIntegrationHandoff.md §H](PraxisIntegrationHandoff.md#9-workstream-h--supervision) |

---

## Relationship to other docs

| Doc | What it covers | Relationship |
|---|---|---|
| **This doc** | Action categories, required components, adapter contracts, new-action checklist, Praxis integration quick-reference | Start here regardless of which side you are working on |
| [PortalOnboarding.md](PortalOnboarding.md) | Playwright Codegen recording, selector extraction, descriptor skeleton | Follow after this doc to fill in the selectors for a new descriptor |
| [PayrollOrchestrationFlow.md](PayrollOrchestrationFlow.md) | BPMN process model for the monthly payroll cycle; Analysis gates; per-portal specifics | Reference for how the three categories chain together in the payroll context |
| [contract-api/CONTRACT.md](../contract-api/CONTRACT.md) | Wire format, envelope schemas, encryption spec, Java DTOs | Full contract reference; this doc summarizes the parts most relevant to action type design |
| [PraxisIntegrationHandoff.md](PraxisIntegrationHandoff.md) | RabbitMQ plumbing, KMS setup, Secrets Manager, BPMN wiring, deployment, observability | Full integration spec; this doc summarizes the action-type view of it |
| [PortalDeployment.md](PortalDeployment.md) | Production rollout: worker image, credential provisioning, BPMN wiring, staging rehearsal | Follow after local descriptor is working and fixture-tested |
