# Payroll Envelope Contract — v1

Wire-format spec for the JSON envelopes that flow between Praxis BPM and the agent worker. Companion to [PayrollOrchestrationFlow.md](../PayrollOrchestrationFlow.md), which describes how these envelopes plug into the BPMN process model.

**Versioning:** `payroll-*.v1.*`. Major-version bumps happen when the cleartext body shape changes incompatibly. Schemas are JSON Schema 2020-12 under [`src/main/resources/schemas/v1/`](src/main/resources/schemas/v1/).

---

## 1. Envelope types

| Schema | Producer | Consumer | Purpose |
|---|---|---|---|
| `payroll-capture-result.v1` | Agent worker (capture-side adapter, e.g. AutoPlanilla) | BPM Service Task → next stage | Result of pulling payroll from a source-of-truth portal |
| `payroll-submit-request.v1` | BPM (built from a capture result + per-target operation flag) | Agent worker (target-side adapter, e.g. CCSS Sicere, INS RT-Virtual, Hacienda OVI, mock-payroll) | Input to a submit Service Task |
| `payroll-submit-result.v1` | Agent worker (target-side adapter) | BPM Receive Task → exclusive gateway | Per-target submission outcome with totals + `roster_diff` |

All three share a common envelope structure:

```
{
  "schema":     "<schema-name>.v1",
  "envelope":   { envelopeId, businessKey, firmId, locale, createdAt, issuer, issuerRunId },
  "task":       { type, operation, sourcePortal|targetPortal, period, planilla },
  "encryption": { scheme, keyName, keyVersion, ciphertextField } | null,
  "result"  | "request": <body object> | "vault:vN:<base64>",
  "audit":      { manifestPath, harSha256, screenshotSha256, payloadSha256 }
}
```

When `encryption` is present, the matching payload field is a `vault:vN:...` ciphertext string. When `encryption` is null, it's a cleartext body object. The agent always emits encrypted; cleartext mode exists for tests and contract examples.

---

## 2. Envelope routing rules (BPM side)

| Field | Rule |
|---|---|
| `envelope.envelopeId` | UUID v4. Producer-generated. **Idempotency key.** Workers store seen ids in `ExecutionState` (M5+) and reject duplicates. |
| `envelope.businessKey` | Producer-generated, format `<period>::<planillaId-or-name>` (e.g. `2026-02::1051`). Flowable correlation key for Receive Tasks. |
| `envelope.firmId` | Tenant scope. Drives Vault key resolution AND determines which credentials the agent fetches. **Required on every envelope.** |
| `envelope.locale` | BCP-47 (`es`, `en`, `es-CR`). Used for any human-facing strings the worker emits (HITL prompts, error messages). |
| `envelope.issuer` | Identifies the producer software, e.g. `agent-worker/autoplanilla` or `praxis-bpm/payroll-process`. |
| `envelope.issuerRunId` | Producer-side correlation. For agent envelopes this is the run directory name in `artifacts/`. For BPM envelopes it's the process instance id. |
| `task.sourceCaptureEnvelopeId` (submit envelopes only) | Links a submit run back to the capture envelope it derived from. Required for audit chain-of-custody. |
| `task.clientIdentifier` (optional) | **Shared-creds portals only.** The portal-side identifier (cédula jurídica, internal client code, etc.) that selects which client to act on after NeoProc's shared login. Not a secret; lives on the firm record in Praxis, not in Vault. BPM must populate this when dispatching to any portal whose descriptor declares `credentialScope: shared`. Omit for per-firm portals. |

**BPM commitments to honor:**

1. Treat unknown `result.status` values as escalation. Don't silently advance the process.
2. Echo `envelope.businessKey` back on any callback so the worker can correlate.
3. Pass the upstream capture envelope verbatim into the submit task payload — no transformation. The worker re-verifies via `audit.payloadSha256`.
4. For shared-creds portals, BPM must include `task.clientIdentifier` in the submit envelope. Workers reject shared-portal envelopes that lack it rather than guess. See [PraxisIntegrationHandoff.md §3 B.4](../PraxisIntegrationHandoff.md#b4-scoping-enforcement) for the scoping rationale.

---

## 3. Status routing

```
SUCCESS  → end success
PARTIAL  → inclusive gateway → register-hires / deregister-terminations subprocess(es)
MISMATCH → user task: data integrity review
FAILED   → user task: engineering escalation
```

See [PayrollOrchestrationFlow.md §4](../PayrollOrchestrationFlow.md#4-status-vocabulary--routing) for full semantics.

---

## 4. Encryption

### Scheme

```
encryption.scheme:          "vault-transit-v1" | "local-aes-gcm-v1"
encryption.keyName:         "payroll-firm-<firmId>"
encryption.keyVersion:      <integer, embedded in the vault:vN: prefix>
encryption.ciphertextField: "result"   (or "request" for submit-request)
```

Both schemes produce a wire-identical `vault:vN:<base64>` ciphertext format so an envelope captured in dev (under `local-aes-gcm-v1`) decrypts cleanly under `vault-transit-v1` after the corresponding Vault transit key is provisioned.

### Local dev (today)

`LocalDevCipher` in `common-lib` implements `local-aes-gcm-v1`:
- AES-256-GCM, 96-bit random IV, 128-bit auth tag.
- Per-firm keys at `~/.financeagent/cipher-keys/payroll-firm-<firmId>` (auto-created on first encrypt).
- `keyVersion` is always `1` in dev — rotation is a Vault concern.

Selection is environment-driven:
```
FINANCEAGENT_CIPHER=local   (default — uses LocalDevCipher)
FINANCEAGENT_CIPHER=vault   (uses VaultTransitCipher — currently a stub)
```

### Production (Praxis-side runbook)

Praxis must complete three things to switch from `local-aes-gcm-v1` to `vault-transit-v1`:

1. **Mount the transit engine** in the existing Vault deployment:
   ```bash
   vault secrets enable transit
   ```

2. **Add a Vault Java client** to Praxis's `pom.xml`. Either:
   - Spring Cloud Vault (`spring-cloud-starter-vault-config`) — heavier, full integration with Spring property sources, OR
   - Plain Spring `WebClient` against Vault REST — lighter, ~50 lines of code in a `VaultTransitClient` bean.

   Either works. Recommend `WebClient` since Praxis only needs encrypt/decrypt against transit; the full Spring Cloud Vault stack is overkill.

3. **Provision a transit key per firm at firm-onboarding time.** This belongs in the existing `Firm` onboarding service:
   ```bash
   vault write -f transit/keys/payroll-firm-<firmId>
   ```
   The endpoint is idempotent — re-onboarding does not regenerate the key. Suggested integration point: a new `FirmOnboardingListener` bean that listens for `FirmCreatedEvent` and posts to Vault.

Once those three are done, `VaultTransitCipher` (already in `common-lib`) becomes the live impl. Replace the `throw new UnsupportedOperationException` body with the Vault REST call — payload shape:
```json
POST /v1/transit/encrypt/payroll-firm-<firmId>
{ "plaintext": "<base64 of cleartext>" }
→ { "data": { "ciphertext": "vault:v3:..." } }
```

The response's `ciphertext` field is exactly the wire format the contract uses — no string manipulation needed.

---

## 5. Java DTOs

Records under `com.neoproc.financialagent.contract.payroll`:

| Record | Purpose |
|---|---|
| `PayrollCaptureResult` | Top-level capture envelope |
| `PayrollSubmitRequest` | Top-level submit-request envelope |
| `PayrollSubmitResult`  | Top-level submit-result envelope |
| `EnvelopeMeta`         | The `envelope` block (every envelope) |
| `CaptureTask`, `SubmitTask` | The `task` block per envelope type |
| `Period`, `Planilla`   | Sub-records on `task` |
| `Encryption`           | The `encryption` block (when payload is encrypted) |
| `CaptureResultBody`    | Cleartext body of capture envelopes |
| `SubmitRequestBody`    | Cleartext body of submit-request envelopes |
| `SubmitResultBody`     | Cleartext body of submit-result envelopes |
| `RosterDiff`           | The `roster_diff` block on submit results — drives lifecycle subprocess routing |
| `EmployeeRow`          | Employee row in capture results |
| `EnvelopeStatus`       | `SUCCESS` / `PARTIAL` / `MISMATCH` / `FAILED` constants |
| `Audit`                | Per-spec §5 hashes + artifact pointers |

All records are Jackson-serializable. The `result` / `request` field is typed `Object` so it can hold either the cleartext body record OR a Vault-format ciphertext string — Jackson handles both via the runtime type.

---

## 6. Consuming this contract from Praxis

Praxis pulls `contract-api-1.0.0-SNAPSHOT.jar` as a Maven dependency:

```xml
<dependency>
    <groupId>com.neoproc.financialagent</groupId>
    <artifactId>contract-api</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

The agent-worker module is **not** on the Praxis classpath. Praxis sees only the contract DTOs + JSON Schemas + this document.

### Building a submit-request from a capture result (Praxis Service Task pattern)

```java
PayrollCaptureResult capture = /* received from upstream Service Task */;
CaptureResultBody captureBody = decryptIfNeeded(capture);  // helper Praxis writes

PayrollSubmitRequest submit = new PayrollSubmitRequest(
    PayrollSubmitRequest.SCHEMA,
    new EnvelopeMeta(
        UUID.randomUUID().toString(),
        capture.envelope().businessKey(),         // carry through
        capture.envelope().firmId(),              // tenant scope persists
        capture.envelope().locale(),
        Instant.now(),
        "praxis-bpm/payroll-process",
        processInstanceId),
    SubmitTask.forSalaries(
        "ccss-sicere",                            // or ins-rt-virtual / hacienda-ovi
        capture.envelope().envelopeId(),          // chain-of-custody
        capture.task().period(),
        capture.task().planilla()),
    /* encryption metadata — re-encrypt under same firmId key */,
    /* request: SubmitRequestBody built from captureBody.employees() */,
    new Audit(null, null, null, payloadSha256));
```

The agent-worker on the receiving end deserializes via `PayrollSubmitRequest.class` and processes.

---

## 7. Local end-to-end test (no Praxis required)

You can wire AutoPlanilla → mock-payroll directly without going through Praxis, using the capture envelope as input:

```bash
# 1. Run AutoPlanilla capture — emits payroll-capture-result.v1.json into artifacts/<runId>/
mvn -pl agent-worker exec:java \
  -Dportal.id=autoplanilla \
  -Dparams.firmId=12345 \
  -Dparams.planillaName="NeoProc Quincenal USD" \
  -Dparams.fechaInicio=2026-02-01 \
  -Dparams.fechaFinal=2026-02-28

# 2. Note the runId printed above, then run mock-payroll consuming that envelope
mvn -pl agent-worker exec:java \
  -Dportal.id=mock-payroll \
  -Dparams.firmId=12345 \
  -Dparams.source.captureEnvelope=artifacts/<runId>/payroll-capture-result.v1.json
```

The mock-payroll adapter reads each captured employee, fuzzy-matches against the harness's hardcoded INS-style roster (15 employees from your screenshot), fills any matched salaries, submits, scrapes the confirmation totals, and emits `payroll-submit-result.v1.json` with the `roster_diff` populated.

For a happy-path SUCCESS the captured roster needs to overlap with the hardcoded harness roster — which it won't, since AutoPlanilla's NeoProc Quincenal USD planilla has different cédulas. **That's the point**: you'll see `status=PARTIAL` with both `missingFromPortal` (every captured employee) and `missingFromPayroll` (every harness employee) populated, demonstrating the lifecycle-routing signal end-to-end.

---

## 8. Backward compatibility & versioning

- Adding a new field to a body record: minor (no schema rev needed for additive changes; Jackson tolerates unknown fields by default).
- Renaming or removing a field: major (`v2`).
- Adding a new `task.operation` value: minor — consumers that don't recognize it MUST escalate (per §2 commitment 1), not silently no-op.
- Adding a new envelope type (e.g. `employee-register.v1`): minor — new schema file under `schemas/v1/`, new top-level DTO record. Existing schemas unchanged.

When v2 schemas land, they live in `schemas/v2/` alongside v1; old envelopes still validate.
