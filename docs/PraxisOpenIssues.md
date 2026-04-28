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
