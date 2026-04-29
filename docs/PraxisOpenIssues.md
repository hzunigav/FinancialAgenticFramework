# Praxis Open Issues

Open contract divergences and bugs that need a Praxis-side fix. Each entry is
self-contained so it can be lifted into a Linear/Jira ticket as-is.

---

## OI-001 — Worker dev default emits a cipher scheme Praxis cannot read (workflow stalls forever)

**Status:** worker-side fix landed 2026-04-28 (default cipher flipped to
cleartext, schema enum tightened, runbook updated). Documented here as the
diagnosis-of-record and to flag the schema change for Praxis's next contract sync.

**Found:** 2026-04-28 during local smoke test of the AutoPlanilla capture
flow against this Praxis instance. Initial diagnosis blamed RabbitMQ queue
wiring (drift between worker-side and Praxis-side queue naming); that was a
red herring — Praxis's response correctly identified the actual cause as a
wire-cipher mismatch.

### Symptom

A `Payroll Cycle` workflow instance stays on **"Wait for capture result"**
indefinitely after the worker publishes a successful `payroll-capture-result.v1`
envelope. No errors anywhere — no DLQ activity, no Praxis exception log, no
worker error. The result queue is empty because Praxis's listener is
acking-and-discarding the message.

Reproduced on workflow instances **#3604** and **#3605**.

### Root cause

The worker's local-dev default cipher (`FINANCEAGENT_CIPHER=local` →
`LocalDevCipher`) emits `Encryption.scheme = "local-aes-gcm-v1"` on the wire.
Per [PraxisIntegrationHandoff.md §A.3](PraxisIntegrationHandoff.md#a3-implement-kmsenvelopeclient-in-praxis):

> **Critical asymmetry — `decrypt()` is NOT called on inbound results.**
> Inbound `payroll-capture-result.v1` and `payroll-submit-result.v1` envelopes
> either arrive in cleartext (no `encryption` block) or with `vault:v1:`
> ciphertext in the `result` field — but Praxis does not decrypt the body
> before passing it into the BPMN process.

So Praxis only handles **cleartext** or **`kms-envelope-v1`** on inbound
results. `local-aes-gcm-v1` is a worker-internal at-rest scheme, never a wire
format. With encrypted opaque ciphertext where Praxis expects to navigate
into a structured `result` object, the BPMN process silently fails to bind
the result to the workflow variable and the Receive Task never advances.

### Why the queue evidence misled the first diagnosis

RabbitMQ stats at the time of the stall:

| Queue                       | publish | deliver | ack | consumers |
| --------------------------- | ------- | ------- | --- | --------- |
| `financeagent.results`      | 7       | 7       | 7   | 1         |
| `praxis.agent.result.queue` | 0       | 0       | 0   | 1         |

The first interpretation was that Praxis's BPMN listener was bound to the
non-spec `praxis.agent.result.queue` and a no-op consumer was draining the
spec queue. The corrected interpretation: the BPMN listener IS the consumer
on `financeagent.results` (matching the spec), it just acks-and-drops because
the body is unreadable. `praxis.agent.result.queue` is unrelated to this
flow — likely a different Praxis-internal subsystem.

### Worker-side fix (landed 2026-04-28)

1. **`EnvelopeIo.defaultCipher()`** — the unset default is now
   `CleartextCipher`, not `LocalDevCipher`. `FINANCEAGENT_CIPHER=cleartext` is
   accepted as a synonym for the existing `=none`. `=local` is still
   accepted but its scope is narrowed to offline/CLI runs; the javadoc on
   `LocalDevCipher` now explicitly flags it as not-wire-compatible with
   Praxis.
2. **`payroll-capture-result.v1.json`** — `local-aes-gcm-v1` removed from
   the `Encryption.scheme` enum, completing the cleanup the changelog at v1.3
   of `PraxisIntegrationHandoff.md` claimed was already done.
3. **Runbook + onboarding docs** updated to use
   `FINANCEAGENT_CIPHER=cleartext` for any local dev where Praxis is the
   consumer, with an explicit "don't use `=local` when Praxis is in the loop"
   troubleshooting row.

### Praxis-side asks

1. **Confirm contract sync.** The `Encryption.scheme` enum on
   `payroll-capture-result.v1` is now `["vault-transit-v1", "kms-envelope-v1"]`.
   The handoff changelog already claimed this; the schema file just hadn't
   been updated. No code change should be needed on Praxis's side, but if
   any Praxis validator was tolerating `local-aes-gcm-v1` for development
   compatibility, it can drop that allowance now.
2. **Optional — surface the silent drop.** When Praxis's result listener
   receives a `result` field it cannot deserialize as the expected schema
   (cleartext object) and is not a recognised `vault:v1:` ciphertext, the
   message is currently acked and discarded with no visible signal. Logging
   a warning (or routing to a dedicated DLQ) would have shortened today's
   debug loop from hours to minutes.

### Acceptance test

After the worker fix and any Praxis-side schema sync, the §C.3 acceptance
test from the handoff should pass: a `payroll-submit-request.v1` published
by Praxis for `mock-payroll` results in the agent-worker emitting a
`payroll-submit-result.v1`, and the corresponding Receive Task in the BPMN
workflow advances within seconds.

---

## OI-002 — Error-result envelope failed schema validation, silently DLQ'd every submit error

**Status:** worker-side fix landed 2026-04-29 PM. Documented for the diagnosis-of-record and to flag the symmetric "make silent drops loud" ask to Praxis.

**Found:** 2026-04-29 during the first BPMN-driven E2E run (instance #3752, AutoPlanilla → CCSS Sicere). Manifested as the same Wait-stall symptom as OI-001 but with a different root cause. Worker had completed OI-001's cipher fix; the new stall surfaced the next layer of "silent drop" behaviour.

### Symptom

A `Payroll Cycle` workflow stays on **"Wait for CCSS result"** indefinitely after the worker pulls a submit task. RabbitMQ shows `financeagent.tasks.submit.ccss-sicere` drained to 0 (so the worker definitely received the task) but `financeagent.results` shows no new message. Worker log shows:

```
ERROR run failed envelopeId=<id>
java.lang.IllegalStateException: No credentials found for portal 'ccss-sicere' client '3-101-680139' ...
ERROR cannot publish error result — routing to DLQ envelopeId=<id>
SchemaValidationException: Schema validation failed for payroll-submit-result.v1 (2 errors):
  $.task: property 'period' is not defined in the schema and the schema does not allow additional properties;
  $.task: property 'planilla' is not defined in the schema and the schema does not allow additional properties
WARN  message processing failed — nacking to DLQ
```

Two stacked bugs: (a) the underlying credential-lookup miss (see OI-003 below), and (b) the error-envelope schema violation that prevented the listener from reporting any failure back to Praxis.

### Root cause

`PayrollTaskListener.wrapResult` echoed the request's `SubmitTask` (with `period` + `planilla`) verbatim into result envelopes for FAILED / DUPLICATE_ENVELOPE / minimal cases. The result schema's `task` block declares `additionalProperties: false` and only allows `type`, `operation`, `targetPortal`, `sourceCaptureEnvelopeId`, `clientIdentifier`. Validate-on-publish rejected the envelope, the listener's `try/catch` fell through to DLQ, and Praxis got no signal at all.

This means error reporting was **broken for every submit-side error**, not just the credential miss that triggered today's run. Every prior submit error since the validate-on-publish path landed had been DLQ'ing too, masked by the fact that adapters had stayed inside the happy path.

### Worker-side fix (landed 2026-04-29 PM)

1. **`PayrollTaskListener.wrapResult`** strips `period` + `planilla` when copying the request's `SubmitTask` into the result envelope. The result-side task carries only `targetPortal`, `sourceCaptureEnvelopeId`, `clientIdentifier`.
2. **`SchemaValidatorPojoTest`** locks the contract: a positive case asserts the post-fix transformation validates, a negative case asserts that a result with `period`/`planilla` on the task block fails validation (regression lock).
3. **Memory rule** `feedback_pojo_validation_test_required` extended: error-envelope builders need their own POJO tests, and negative tests are valuable too.

### Praxis-side asks

1. **Optional — surface DLQ activity.** The `financeagent.dlq` queue is shared between worker-side error envelopes and Praxis-side malformed messages. Tooling that surfaces a non-zero DLQ depth (Slack/email/PagerDuty) would have shortened today's debug loop. Same shape as the OI-001 ask.

---

## OI-003 — clientIdentifier dashed-form on the wire was not normalised on lookup

**Status:** worker-side fix landed 2026-04-29 PM. Documented to align Praxis and worker on the canonical wire form.

**Found:** 2026-04-29, same BPMN run as OI-002. Surfaced as "no credentials found" but the secret existed under the dash-free key.

### Symptom

```
java.lang.IllegalStateException: No credentials found for portal 'ccss-sicere' client '3-101-680139'
in C:\Users\...\.financeagent\secrets.properties
(expected keys starting with portals.ccss-sicere.credentials.3-101-680139. — check the clientIdentifier on the request envelope)
```

`secrets.properties` had `portals.ccss-sicere.credentials.3101680139.username/password` (digits-only). Praxis sent `clientIdentifier="3-101-680139"` (cédula jurídica with dashes). Lookup failed because both `LocalFileCredentialsProvider` and `AwsSecretsManagerCredentialsProvider` did exact-string matches, no normalisation.

### Root cause

The cédula jurídica has two equivalent forms: `3-101-680139` (legal-registry display, with dashes) and `3101680139` (internal id, dash-free). CCSS Sicere uses the dash-free form internally; Praxis sends the dashed legal form on the wire. The secret-storage layer hadn't agreed on a canonical form.

### Worker-side fix (landed 2026-04-29 PM)

1. **`CredentialsProvider.normalizeClientId(String)`** static helper strips ASCII hyphens + whitespace; preserves all other characters verbatim so non-cédula identifiers (UUIDs, alphanumeric tenant ids) pass through unchanged.
2. Both `LocalFileCredentialsProvider` and `AwsSecretsManagerCredentialsProvider` call it before composing the lookup key (secrets-properties prefix or AWS Secrets Manager path segment).
3. Either wire form (`3-101-680139` or `3101680139`) now resolves to the same secret. AWS Secrets Manager paths use the dash-free internal id by convention.

### Praxis-side asks

None — the worker now tolerates either form. Praxis can keep emitting the dashed legal form (which is what appears in Costa Rican legal documents) without coordination. Documenting here only so a future Praxis-side change to clientIdentifier formatting doesn't break the contract silently.
