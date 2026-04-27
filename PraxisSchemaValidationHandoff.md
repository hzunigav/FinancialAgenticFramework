# Schema validation handoff — Praxis side

**Audience:** the Praxis BPM team.
**Goal:** make Praxis enforce the same payroll envelope contracts that the agent-worker now enforces. This closes the loop on the kind of bug we hit with Workflow Instance #2501 (capture result published, but rejected/ignored downstream because the producer drifted from the schema and nobody noticed).

---

## 1. What changed on our side

Three things, all in the [`contract-api`](contract-api/) module:

1. **Schemas were tightened to match what is actually emitted.** The two bugs that bit us:
   - `encryption.scheme = "local-aes-gcm-v1"` was missing from the allowed enum. Now allowed alongside `vault-transit-v1` and `kms-envelope-v1`.
   - `task.period` / `task.planilla` were marked `required` but emitted as `null` for portals that don't use them (mock-payroll). They are now optional.
   - `errorDetail.category` enum widened to include `DUPLICATE_ENVELOPE`, `UNCAUGHT_EXCEPTION`, `SCHEMA_VIOLATION`, `OTHER`.
   - New `review` block defined on `payroll-submit-result.v1` to match what `SubmitResultBody.Review` already produces.
   - New `Money` `$def` accepts both JSON string (`"13870000.00"`) and JSON number — Jackson serializes `BigDecimal` as a number by default; the schema previously demanded a string.
   - **New schema file**: `payroll-capture-request.v1.json` (was missing entirely).

2. **A `SchemaValidator` utility now exists in `contract-api`** at [`com.neoproc.financialagent.contract.validation.SchemaValidator`](contract-api/src/main/java/com/neoproc/financialagent/contract/validation/SchemaValidator.java). It loads schemas from the classpath, caches them, and resolves cross-schema `$ref`s under the `https://neoproc.com/financialagent/schemas/` URI prefix to local resources. Throws `SchemaValidationException` on failure.

3. **Validation is now mandatory at three points on the worker side:**
   - **CI** — fixture tests in `contract-api/src/test/java/.../SchemaFixtureValidationTest.java` validate every `valid-*.json` fixture and assert that `invalid-*.json` fixtures are rejected. Build fails if a producer drifts from a schema.
   - **Validate-on-publish** — `PayrollCaptureListener.publishResult()` and `PayrollTaskListener.publishResult()` validate the outgoing envelope before sending. A failure surfaces a worker bug at the wire boundary; the catch block converts it to a `FAILED` result with `category=SCHEMA_VIOLATION` so Praxis still gets a reply.
   - **Validate-on-consume** — both listeners now take a `Message rawMessage` parameter and validate the raw bytes against the request schema before doing any work. Failures throw → `EnvelopeAwareErrorHandler` routes to DLQ with a clear log line.

---

## 2. What we ask Praxis to do

To close the symmetry, Praxis should validate every envelope it consumes from `financeagent.results`, and validate every envelope it publishes to `financeagent.tasks.{capture|submit}.<portal>` before sending.

### 2.1 Pull the updated `contract-api` artifact

The schemas and the `SchemaValidator` ship in the same JAR you already depend on:

```xml
<dependency>
    <groupId>com.neoproc.financialagent</groupId>
    <artifactId>contract-api</artifactId>
    <version>1.0.0-SNAPSHOT</version> <!-- or whatever you bump to -->
</dependency>
```

This already transitively pulls `com.networknt:json-schema-validator`. No new direct dep needed if you bump `contract-api`.

### 2.2 Validate-on-consume for results

In every place Praxis reads a payroll-*-result envelope off `financeagent.results`, call:

```java
import com.neoproc.financialagent.contract.validation.SchemaValidator;

// raw bytes preferred — catches additionalProperties violations that
// lenient deserialization would silently drop
SchemaValidator.validate(amqpMessage.getBody(), SchemaValidator.CAPTURE_RESULT);
// or SchemaValidator.SUBMIT_RESULT
```

On failure (`SchemaValidationException`):

- **Do not advance the workflow token.** A bad envelope must not be silently dropped — that's the failure mode that produced the "Wait for capture result" stall on WFI #2501.
- Log at `WARN` with the full validation message and the `envelopeId` extracted from raw bytes.
- DLQ the message (don't requeue) and create a HUMAN task on the workflow instance ("Result envelope rejected — schema mismatch") so an operator sees it. The existing "Submission failed — engineering escalation" pattern is a good template.

### 2.3 Validate-on-publish for requests

Symmetric to ours: before publishing a `payroll-capture-request.v1` or `payroll-submit-request.v1` to RabbitMQ, validate it. A failure here is a Praxis bug — fail the workflow instance with a clear cause; do not let an invalid envelope reach the queue.

```java
SchemaValidator.validate(requestPojo, SchemaValidator.CAPTURE_REQUEST);
// or SchemaValidator.SUBMIT_REQUEST
rabbitTemplate.convertAndSend(...);
```

### 2.4 Add fixture tests

Mirror our `SchemaFixtureValidationTest`: keep one `valid-*.json` per code path that builds a request, plus a couple of `invalid-*.json` decoys, and assert validation behavior in CI. The fixtures double as living documentation.

---

## 3. Specific things to check on Praxis right now

When you pull the updated schemas, please audit:

1. **What `encryption.scheme` does Praxis emit on outbound requests?** If it's `local-aes-gcm-v1`, you're already aligned. If it's something else (`vault-transit-v1`, custom name), tell us so we can extend the enum or you can normalize.
2. **Does Praxis emit `task.period` / `task.planilla` for mock-payroll captures?** If so they're allowed; if null/omitted, also allowed. No action — but confirm the field your code path actually emits.
3. **`task.targetPortal` enum** is currently `["ccss-sicere", "ins-rt-virtual", "hacienda-ovi", "mock-payroll"]`. If Praxis adds a new target, update the schema in the **same PR** that adds the BPMN — do not let production drift.
4. **`SubmitResultBody.errorDetail.errorClass`** is now `string` without an enum constraint. If Praxis routes on this field (e.g. "if TRANSIENT, retry"), be aware that the worker currently passes a fully-qualified Java class name there, not `TRANSIENT`/`PERMANENT`. We can tighten this together if you want — file an issue.

---

## 4. Process change we're proposing

When either side adds a new field, enum value, status, portal id, cipher scheme, or HITL action:

> **Update the JSON Schema in the same PR that ships the code.** PR is blocked by the fixture-validation test. No exceptions.

Updating "New portal checklist" and "WorkerActionTypes.md" on our end to reflect this; please mirror in your equivalents.

---

## 5. Open questions for you

1. Do you want us to publish a versioned `contract-api-1.1.0` GitHub Packages release with these changes, or stay on `1.0.0-SNAPSHOT` until the next planned bump? Either is fine — let us know which timing fits your release.
2. Are there Praxis-side enums or response shapes you'd want us to schematize too (HITL task forms, signal types)? Right now those are Praxis-internal; happy to add JSON schemas for them if it helps your tests.
3. Is there a Praxis equivalent of our `EnvelopeAwareErrorHandler` for raw-bytes envelope-id extraction? Worth confirming so we know the audit trail is symmetric.

---

## 6. Findings flagged back to agent-worker

Issues Praxis found while integrating against the schemas. Logged here so they don't get lost when `praxis.payroll.encryption.enabled` flips to `true` for E2E.

### 6.1 `Encryption.ciphertextField` enum is `["result"]` only — but submit-request encrypts the `request` field

**State:** dormant. Praxis defaults to `praxis.payroll.encryption.enabled=false`, so no outbound envelope carries an `encryption` block today. Manifests the moment encryption is wired up E2E.

**Symptom (when triggered):** [`PayrollServiceTaskDelegate.java:236`](https://github.com/.../PayrollServiceTaskDelegate.java#L236) sets `encryption.ciphertextField = "request"` on `payroll-submit-request.v1` envelopes — correctly, since the encrypted body in that schema lives in the field literally named `request`, not `result`. With validate-on-publish enabled per §2.3, the schema rejects the envelope (`ciphertextField must be one of ["result"]`) and Praxis fails the workflow instance instead of publishing.

**Root cause:** all three payroll schemas `$ref` the same `Encryption` `$def` from [`payroll-capture-result.v1.json:128`](contract-api/src/main/resources/schemas/v1/payroll-capture-result.v1.json#L128), which hard-codes `enum: ["result"]`. [`CONTRACT.md:77`](contract-api/CONTRACT.md#L77) already documents the intended behavior (`"result"` *or* `"request"` for submit-request), but the schema never matched the doc.

**Collateral:** [`payroll-submit-request.valid.json:22`](contract-api/src/test/resources/fixtures/payroll-submit-request.valid.json#L22) currently asserts `ciphertextField: "result"` — which is also wrong per the doc, and currently slips through only because the schema enum is equally broken. Fixture must be updated alongside the schema or the fixture-validation test will start failing in the opposite direction.

**Fix options:**

| Option | Change | Tradeoff |
|---|---|---|
| A — per-schema `Encryption` `$def` | Inline `Encryption` in each schema with its own one-element enum: `["result"]` for capture-result and submit-result, `["request"]` for submit-request | Cleanest. Each schema becomes self-validating: the `enum` proves the wire-field name matches the schema's actual encrypted field. ~3 lines × 3 schemas of duplication. |
| B — widen the shared enum to `["result", "request"]` | One-line change in `payroll-capture-result.v1.json` | Weakens validation: a malformed capture-result with `ciphertextField: "request"` (a field that doesn't exist in that schema) would now pass schema. |

Praxis recommends Option A.

**Triggering condition for re-test:** flip `praxis.payroll.encryption.enabled` to `true` and run a submit cycle E2E. Validate-on-publish will surface the violation immediately.

---

## 7. Praxis confirmations received 2026-04-27 (this round)

Resolutions to §3 (specific things to check) and §5 (open questions) — captured here so neither side has to re-derive them.

| Question | Praxis answer |
|---|---|
| §3.1 `encryption.scheme` Praxis emits | `kms-envelope-v1` (renamed today from `aws-kms-envelope-v1`). ✅ aligned with the worker's `KmsEnvelopeCipher.schemeName()`. |
| §3.2 `task.period` / `task.planilla` for mock-payroll | Populated when the user provides them at cycle start; not currently emitted as null. Schema accepts both — no behavioral risk. |
| §3.3 `task.targetPortal` enum | Praxis uses exactly the four listed values (`ccss-sicere`, `ins-rt-virtual`, `hacienda-ovi`, `mock-payroll`). No additions planned. |
| §3.4 `errorDetail.errorClass` routing | Praxis does **not** route on this field. Freeform string is fine; tightening to `TRANSIENT`/`PERMANENT` would be paperwork. Closed. |
| §5.1 contract-api versioning | Stay on `1.0.0-SNAPSHOT`; no need to cut `1.1.0`. Praxis pulls from GitHub Packages on every build. |
| §5.3 raw-bytes `envelopeId` extraction symmetry | Praxis's `PayrollResultListener.extractContext()` parses raw bytes via `ObjectMapper.readTree` (no schema dependency) so `envelopeId` and `businessKey` are available even when validation fails. ✅ symmetric to the worker's `EnvelopeAwareErrorHandler`. |

§5.2 (Praxis-side schemas for HITL signal types) — not flagged in this round; revisit when bespoke HITL UI work starts post-v1.

---

## 8. Praxis-side shipped 2026-04-27 (FYI for the agent-worker team)

- **Capture publish wire format changed.** Previous: `payroll-capture-result.v1` with a `PENDING` placeholder body. Now: the new `payroll-capture-request.v1` shape with `request: null`. Aligned with the schema added in this round.
- **Validate-on-publish.** Outbound envelopes that fail schema throw `BpmnError(PAYROLL_TASK_SCHEMA, ...)` and never reach the broker.
- **Validate-on-consume.** Malformed inbound results no longer silently stall the receive task. Praxis creates a HITL `WorkflowTask` titled *"Result envelope rejected — schema mismatch"* on the matching workflow instance, plus a `payroll.envelope.rejected` audit event. The 10-minute boundary timer remains the backstop. Operationally: a malformed result surfaces in the supervisor dashboard within seconds, not after 10 minutes of stall.

---

## 9. Worker confirmation: capture-queue consumer (answer to Praxis's blocking question)

> *"Does a worker subscribe to `financeagent.tasks.capture.{portal}` queues?"*

**Yes — one worker process per `PORTAL_ID` consumes BOTH the capture and submit queues for that portal.** No separate `PORTAL_ID=capture` worker is required. For tonight's E2E with `captureSourcePortal=mock-payroll`, a single worker booted with `PORTAL_ID=mock-payroll` covers it.

Wiring lives at [`agent-worker/src/main/resources/application.yml`](agent-worker/src/main/resources/application.yml):

```yaml
portal:
  id: ${PORTAL_ID:mock-payroll}
agent:
  worker:
    submit-queue:  financeagent.tasks.submit.${portal.id}
    capture-queue: financeagent.tasks.capture.${portal.id}
```

Both `@RabbitListener`s bind from these properties: `PayrollCaptureListener` on the capture queue, `PayrollTaskListener` on the submit queue. Verified end-to-end on 2026-04-27 — a single worker (`PORTAL_ID=mock-payroll`) shows two active consumers in the RabbitMQ management UI on the matching pair of queues, processes both message types, and publishes correlated results to `financeagent.results`. The capture queue will not dead-letter as long as a worker for that portal is running.

In production each per-portal worker (CCSS, INS, Hacienda, AutoPlanilla) handles its own pair the same way.

---

## 10. Bugs caught during the 2026-04-27 sanity check

Five bugs that would have re-burned the cross-system E2E were found *before* re-attempting it, and all are fixed in this round. Captured here so the failure modes don't recur.

| # | Bug | How it would have manifested | Fix |
|---|---|---|---|
| 1 | `json-schema-validator` was a transitive-only dependency on `agent-worker`; spring-boot's runtime classpath did not include it. | Worker died with `NoClassDefFoundError: SpecVersion$VersionFlag` on the first inbound message — i.e., 100% of E2E runs failed at the first listener invocation. | Added `<dependency>` directly in [`agent-worker/pom.xml`](agent-worker/pom.xml). Transitive resolution from `contract-api` was unreliable under `spring-boot:run`. |
| 2 | `SchemaValidator.MAPPER` did not disable `WRITE_DATES_AS_TIMESTAMPS`. | Validate-on-publish saw `envelope.createdAt` as a numeric epoch (Jackson default for `Instant`) instead of an ISO string and rejected every result envelope. The on-disk envelopes were correct (different mapper); only the validator's POJO path was broken. | Added `.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)` to mirror `EnvelopeIo.MAPPER`. |
| 3 | `payroll-capture-result.v1.json` required `totals.renta` to be a non-null `Money`, but the mock-payroll capture adapter (and any real portal that doesn't track renta) emits null. | Every mock-payroll capture rejected at validate-on-publish. | Made `renta` accept null via `oneOf [null, Money]`; semantics preserved (null = "unknown", not zero). |
| 4 | `PayrollCaptureListener.buildFailedResult` and `buildMinimalResult` constructed `CaptureResultBody` with `totals=null`; same in `PayrollTaskListener`. Schema requires `totals` always. | Any internal failure (timeout, idempotency, uncaught exception) produced a FAILED-result envelope that itself failed schema → DLQ; Praxis never received a correlated FAILED reply, only the 10-minute timer fired. | Both builders now populate a zero `Totals` placeholder. |
| 5 | `RosterDiff.isEmpty()` was being serialized as JSON property `empty` because Jackson treats `isXxx()` as a boolean getter. The submit-result schema has `additionalProperties: false` on `roster_diff`, so this property would be rejected. | Every submit-result envelope rejected at validate-on-publish, same impact as #4. | `@JsonIgnore` on `isEmpty()`. |
| 6 (related) | Submit-result schema's `totals.grandTotal` was a raw string-pattern instead of the `Money` `$ref`. Capture-result was migrated; submit-result was missed. | Submit-result POJOs serialize `BigDecimal` as JSON number → schema rejected. | Changed to `$ref: Money` to mirror capture-result. |

**Test coverage added** to prevent regression:
- New [`SchemaValidatorPojoTest`](contract-api/src/test/java/com/neoproc/financialagent/contract/validation/SchemaValidatorPojoTest.java) — drives `SchemaValidator.validate(Object)` with freshly-built POJOs containing real `java.time.Instant` createdAt, null `renta`, and the FAILED-result shapes both listeners produce. The fixture-driven `SchemaFixtureValidationTest` only exercises the byte-array path with pre-formatted ISO strings — that gap was the reason none of these bugs were caught at the contract-api level before today.

---

Ping `@hzunigav` (financial-agent side) when you've integrated; we'll re-run a fresh Payroll Cycle end-to-end and verify both ends now reject schema-violating envelopes loudly instead of silently stalling.
