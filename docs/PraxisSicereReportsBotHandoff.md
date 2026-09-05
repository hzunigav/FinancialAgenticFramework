# Handoff to Praxis — CCSS Sicere report-archival bot, and a generic bot-dispatch contract

**Status:** implemented, pending queue provisioning · **Owner:** NeoProc (agent-worker) · **Consumer:** Praxis BPM
**Created:** 2026-09-04

This document has two halves and they can be adopted independently:

1. **[The bot](#part-1--the-bot)** — what `ccss-sicere-reports` does, what it needs, what it
   produces. Implemented and runnable today via CLI.
2. **[The dispatch contract](#part-2--generic-bot-dispatch)** — a proposal to stop adding a
   queue pair and a schema per bot. Read this before wiring the BPMN, because it changes
   *how* you launch the bot (not *what* it needs).

---

## Part 1 — the bot

### What it does

Logs in to CCSS Sicere and archives the reports that exist **only while the planilla for a
period is still `preelaborada`** — the submittal window. Once that window closes the reports
are no longer retrievable, which is the whole reason this is automated.

Five reports, each in PDF (archival record) and Excel (machine-readable figures):

| Report | Portal page |
|---|---|
| Detalle de Cuotas | `/factura/listado/index.xhtml` |
| Detalle de Facturación | `/factura/verFacturas/reporteDetalleFacturacion.xhtml` |
| Planilla | `/planilla/imprimir/reportePlanilla.xhtml` |
| Exoneración Trabajadores | `/factura/exoneraciones/index.xhtml?tipReporteExo=Trabajadores` |
| Exoneración Patrono | `/factura/exoneraciones/index.xhtml?tipReporteExo=Patronos` |

Detalle de Cuotas takes no parameters and is PDF-only. The other four are driven by a period
and an output format. **Nine documents per successful run.**

### When Praxis should launch it

**After the CCSS payroll submit is confirmed, in the same submittal window.** It is a
*separate* run from the submit, deliberately: a report failure must never flip a confirmed
payroll submit to failed, and a partial archive should be retryable on its own.

There is no hard coupling — the bot does not read the submit result. It only needs the
period. If it runs before the planilla is preelaborada, or after the window closes, the
portal returns *"No se encontró una planilla preelaborada para el período indicado."* and
the affected reports come back `PORTAL_ERROR` (see [failure semantics](#failure-semantics)).

### What Praxis must send

| Param | Required | Example | Notes |
|---|---|---|---|
| `clientIdentifier` | **yes** | `"3101680139"` | Corporate id (cédula jurídica). Drives credential lookup — byte-for-byte the segment in the secret path. Same value you already send for `ccss-sicere` submit. |
| `period` | **yes** | `"08/2026"` | `MM/yyyy`. **The period the payroll covers, not the month the run happens in.** Defaults to the prior month if omitted, which is right for a run just after month-end and wrong for a re-run months later. |
| `firmId` | **yes** | `1` | As today. |
| `formats` | no | `"pdf,xls"` | Defaults to `pdf,xls`. Use `"pdf"` for archive-only. |

**Do not send a company name.** The bot reads the employer name off the portal itself, so
filenames always match the account the documents actually came from. Passing it in would let
a mismatched parameter file one client's reports under another client's name.

### What comes back

**File naming** — `<FileType><CompanyName><Month><Year>.<ext>`:

```
PlanillaNeoprocSociedadAnonima082026.pdf
PlanillaNeoprocSociedadAnonima082026.xls
DetalleCuotasNeoprocSociedadAnonima082026.pdf
ExoneracionPatronoNeoprocSociedadAnonima082026.pdf
```

`FileType` ∈ `DetalleCuotas`, `DetalleFacturacion`, `Planilla`, `ExoneracionTrabajadores`,
`ExoneracionPatrono`. `CompanyName` is the portal's employer name, accent-folded to ASCII
and capped at 40 characters. `Month`/`Year` come from `period`, so a re-run months later
still files under the period it archived. A malformed `period` yields `000000` rather than a
plausible-but-wrong month.

**S3 location.** Yes — artifacts land in the standard per-run prefix
(`docs/ArtifactStorage.md`), with the documents under `reports/`:

```
s3://<bucket>/<envPrefix>/runs/ccss-sicere-reports/<runId>/manifest.json
s3://<bucket>/<envPrefix>/runs/ccss-sicere-reports/<runId>/reports/PlanillaNeoproc...082026.pdf
s3://<bucket>/<envPrefix>/runs/ccss-sicere-reports/<runId>/reports/index.txt
```

`reports/index.txt` lists every report with its outcome. As with existing flows, the
`manifest.json` URI is surfaced as `audit.manifestPath`; strip `/manifest.json` and append
`reports/<filename>` to address a document. Bucket lifecycle expires objects after 90 days —
**if these reports are a compliance artifact, they need copying to durable storage before
then.** Flagging it rather than assuming; it may need a decision on your side.

### Failure semantics

| Run status | Meaning | Suggested BPMN handling |
|---|---|---|
| `SUCCESS` | All expected documents captured | Continue |
| `PARTIAL` | Some captured, some not | Continue, but raise for review — likely a closed window or an empty report |
| `FAILED` | Nothing captured | Retry once, then escalate |

A single unavailable report never aborts the others. Per-report outcomes are recorded as
manifest steps (`report:<key>-<format>`) and in `reports/index.txt`, so a `PARTIAL` can be
triaged without re-running.

**Known benign case:** for some employers *Exoneración Patrono* legitimately returns an
empty report (title row only, no data) while still producing a valid PDF. That is a
`SUCCESS`, not an error. Confirm with the accounting team whether an empty Exoneración
Patrono is expected for a given client before treating it as a defect.

### Timing

A full run is roughly **60–90 seconds**: fresh login (~4s), then nine documents at ~4–8s
each, plus a mandatory 2s gap between reports (CCSS sits behind an F5 WAF and throttles).

**Size the BPMN Receive Task at 30 minutes, not 90 seconds.** Execution time is not the
number that matters. CCSS permits only one active session per company, so a report run for a
company whose payroll is still submitting will **wait for that payroll to finish** before it
starts — by design, and a large payroll can run close to 30 minutes. The timeout has to cover
queue wait plus execution, so it should match the queue's visibility timeout (1800s).

This is also why the trigger should be the payroll's *confirmation*, not a timer: fire it on
confirmation and the wait is usually zero, because the session has just been released.

---

## Part 2 — generic bot dispatch

### The problem

Today each automation costs a queue pair, a request schema, a result schema, a
`contract-api` republish, and a worker deployment pinned to one `PORTAL_ID`. That is fine
for three portals and does not survive twenty. Concretely, adding this one report bot under
the current pattern would mean:

- `${prefix}-financeagent-tasks-reports-ccss-sicere` + a result queue
- `sicere-reports-request.v1.json` + `sicere-reports-result.v1.json` in `contract-api`
- a republish to GitHub Packages, which Praxis must consume before it can send anything
- another Fargate service

### The proposal

**A fixed envelope and a `botId` that says what to run.** Adding a bot then becomes a
worker-side change with **no contract change and no republish** — which is the property
that actually makes this scale.

Note what that does *not* say: it says nothing about how many queues there are. The
contract and the queue topology are independent, and conflating them is a mistake — the
expensive part of "n queues with n contract changes" was always the contract half. With a
generic envelope, queue count becomes a pure capacity-and-isolation decision you can
revisit later without Praxis changing anything but a destination.

Good news: the result side is **already** consolidated —
`${prefix}-financeagent-results` is shared across portals today. This mostly formalises
what exists and applies it to the request side.

**Request** — `agent-task-request.v1`:

```jsonc
{
  "schema": "agent-task-request.v1",
  "envelope": {
    "envelopeId": "…", "businessKey": "…", "firmId": 1,
    "locale": "es", "issuedAt": "…", "issuer": "praxis", "issuerRunId": "…"
  },
  "task": {
    "botId": "ccss-sicere-reports",   // dispatch key — the only new concept
    "params": {                        // free-form object, bot-specific
      "clientIdentifier": "3101680139",
      "period": "08/2026",
      "formats": "pdf,xls"
    }
  }
}
```

**Result** — `agent-task-result.v1`:

```jsonc
{
  "schema": "agent-task-result.v1",
  "envelope": { /* businessKey echoed verbatim — see below */ },
  "task": { "botId": "ccss-sicere-reports" },
  "status": "SUCCESS | PARTIAL | FAILED",
  "error": { "category": "PORTAL_REJECTED", "message": "…" },
  "artifacts": {
    "folder": "s3://bucket/prod/runs/ccss-sicere-reports/20260904T171400-a1b2c/",
    "files": [
      { "name": "reports/PlanillaNeoprocSociedadAnonima082026.pdf",
        "uri": "s3://bucket/prod/runs/ccss-sicere-reports/20260904T171400-a1b2c/reports/PlanillaNeoprocSociedadAnonima082026.pdf",
        "bytes": 367279 },
      { "name": "manifest.json", "uri": "s3://…/manifest.json", "bytes": 4211 }
    ]
  }
}
```

`artifacts.folder` is the run prefix (trailing slash included) and `artifacts.files` lists
every uploaded object with a directly fetchable URI, so you never have to reconstruct the
key layout. Bot documents are under `reports/`; the rest are operational artifacts
(`manifest.json`, `report.png`, `network.har`).

There is **no `audit` block** on this envelope. The shared `Audit` type requires a
`payloadSha256` over an encrypted body, and this result has no encrypted body — populating
it would mean inventing a hash over nothing. `artifacts` carries everything a consumer needs,
including the manifest.

A `folder` of `null` means the upload did not happen (no bucket configured, or the upload
failed). The documents then exist only on the worker's disk and should be treated as
**unretrievable** — retry the run rather than hunting for them.

`error.category` reuses the existing enum (`NETWORK`, `SELECTOR`, `CREDENTIALS_INVALID`,
`PORTAL_REJECTED`, `PORTAL_UNREACHABLE`, `SCHEMA_VIOLATION`, `UNEXPECTED`, …) so your
existing error-handling branches keep working unchanged.

**`businessKey` is echoed verbatim, exactly as today.** The worker never synthesises one.
Whatever Praxis sets on the request comes back on the result, both inside the envelope and
as the SQS message attribute. Nothing about this proposal changes that.

### How validation survives a free-form `params`

The obvious objection: if `params` is an open object, a typo like `periodo` instead of
`period` is no longer caught by the shared schema.

The answer is to **move per-bot validation out of the published contract and into the bot's
own descriptor**, which ships inside the worker:

```yaml
# ccss-sicere-reports.yaml
params:
  clientIdentifier: { required: true, pattern: '^\d{9,12}$' }
  period:           { required: true, pattern: '^\d{2}/\d{4}$' }
  formats:          { required: false, default: 'pdf,xls' }
```

The worker validates on receipt and fails fast with `SCHEMA_VIOLATION` and a message naming
the offending param. You get the same protection, delivered as a clear result envelope
rather than a queue-level rejection — and a new bot never touches `contract-api`.

### Queue topology: one FIFO queue per portal family

**One request queue per portal family** — `sicere`, `ins`, `hacienda`, `xero` — not one
queue per bot, and not one queue for everything. Every bot for a given portal shares its
family's queue.

Why not one queue for everything: run durations differ by an order of magnitude. A large
payroll takes up to 30 minutes; a report pull takes about 90 seconds. Sharing one queue and
one consumer pool means the 90-second job with a hard submittal-window deadline waits behind
the 30-minute one, and you cannot give SICERE more capacity than Hacienda. Per-family queues
give independent autoscaling (the existing scaling lambda already scales on queue depth) and
contain a stuck family.

Why not one queue per bot: that is the cost the generic envelope exists to remove. Adding a
bot to an existing portal adds nothing — no queue, no schema, no republish.

**Every request queue must be FIFO** (`.fifo` suffix). This is not a preference. SICERE and
INS permit only **one active session per set of credentials**, and FIFO's one-in-flight-per-
message-group guarantee is what enforces that across separate worker tasks. FIFO cannot be
enabled on an existing queue, so the queues must be created FIFO from the start.

### `MessageGroupId` — the part that must be right

**`MessageGroupId` is the identity of the login the run will occupy.** Get this wrong and
FIFO silently guarantees nothing.

The obvious choice — the bot id — is **wrong**. `ccss-sicere` (payroll submit) and
`ccss-sicere-reports` use the *same* company login, so keying on bot id puts them in
different groups and lets a report pull start mid-payroll. The portal then drops one of the
two sessions.

The correct key follows the **credential scope**, because that is what determines how many
logins exist:

| Portal | Credential scope | `MessageGroupId` | Effect |
|---|---|---|---|
| INS RT-Virtual | shared — one login for everyone | `ins-rt-virtual` | every INS run serialises fleet-wide |
| CCSS Sicere (all bots) | per company | `ccss-sicere::client:<clientIdentifier>` | one company at a time; **companies run in parallel** |
| Xero | shared | `xero` | serialises fleet-wide |

So a CCSS payroll for `3101680139` and a report pull for `3101680139` serialise, while
`3999999999` proceeds untouched. That is the intended behaviour: **one company's long
payroll must never delay another company's reports.**

Concretely, for the report bot in this document:

```
MessageGroupId        = "ccss-sicere::client:3101680139"
MessageDeduplicationId = <envelope.envelopeId>
```

`MessageDeduplicationId` should be the `envelopeId`, which aligns with the worker's
idempotency store (already keyed on it). Mind SQS's **5-minute deduplication window**: a
deliberate re-send inside 5 minutes carrying the same `envelopeId` is silently dropped, not
delivered. Use a fresh `envelopeId` for a genuine retry.

**Known coupling, stated rather than buried.** The session key is a worker-side fact — it
derives from each descriptor's `credentialScope` — but SQS requires the *sender* to set
`MessageGroupId`. That means Praxis needs the small table above. It is stable (it changes
only when a portal changes its credential model, which is close to never), but it is real
coupling and worth knowing before it is discovered mid-implementation. If you would rather
not carry it, the alternative is a thin dispatcher on our side that computes the group and
re-enqueues; it costs a hop and we would rather not, but say so and we will.

### Head-of-line blocking is intended here

With FIFO, if a run fails and its message returns to the queue, later messages **for that
same group** wait. For "one active session per login" that is exactly right — but it means a
stuck payroll delays that company's reports until the visibility timeout lapses or the
message reaches the DLQ (`maxReceiveCount: 5`).

Queue settings that matter:

- **`VisibilityTimeout` = 1800s (30 min).** It must exceed the *longest* run the queue
  carries, not the average. If it lapses mid-run, SQS redelivers while the original is still
  driving the portal — and the redelivery then collides with the run it duplicated. Raise it
  before onboarding anything slower than a large payroll.
- **DLQ with `maxReceiveCount: 5`**, as today.
- The cost of a high visibility timeout is slower recovery: if a worker dies mid-message,
  nothing retries for 30 minutes.

### What this does not cover

FIFO serialises everything that arrives *through the queue*. It cannot see a run started
outside it — an operator running the CLI runbook against production while a queued run is in
flight would still open a second session. The worker also holds an in-process semaphore
keyed on the same session identity, which catches same-process overlap and a mis-set
`MessageGroupId`, but not a second process. Closing that fully needs a distributed lock
(e.g. a DynamoDB conditional write on the session key); we have not built one, on the
assumption that CLI-against-production is not routine practice. Tell us if it is.

### Trade-offs, stated plainly

| | Today (per-bot queues) | Proposed (FIFO per portal family) |
|---|---|---|
| Add a bot to an existing portal | 2 queues + 2 schemas + republish + deployment | descriptor + adapter, worker-side only |
| Add a new portal | same as above | one FIFO queue, no contract change |
| Session safety | none across tasks | enforced by the broker per login |
| Isolation | per bot | per portal family |
| Autoscaling | per-queue depth | per-family depth |
| Param validation | central, published schema | descriptor-declared, validated in-worker |

### Migration — incremental, nothing breaks

1. **Add** the FIFO queue for one family (`sicere`) alongside what exists.
   `ccss-sicere-reports` is its first consumer. Nothing currently running changes.
2. **Migrate** payroll capture/submit onto it when convenient, running old and new listeners
   in parallel until Praxis switches.
3. **Add** further families (`ins`, `hacienda`) as those automations arrive.
4. **Retire** the per-portal task queues and the divergent bank-statement pair.

Step 1 is enough to launch this bot.

---

## BPMN sketch

```
[Payroll submit confirmed]
          │
          ▼
  ┌───────────────────────────────┐
  │ Service Task: archive reports │  send agent-task-request.v1 to
  │  botId = ccss-sicere-reports  │  <env>-financeagent-tasks-sicere.fifo
  │  params: clientIdentifier,    │
  │          period, formats      │  MessageGroupId =
  │                               │    ccss-sicere::client:<clientIdentifier>
  │                               │  MessageDeduplicationId = envelopeId
  └───────────────────────────────┘
          │
          ▼
  ┌───────────────────────────────┐
  │ Receive Task (30 min timeout) │  correlate on businessKey
  │  agent-task-result.v1         │  from <env>-financeagent-results
  └───────────────────────────────┘
          │
   ┌──────┴───────┬──────────────┐
   ▼              ▼              ▼
 SUCCESS       PARTIAL         FAILED
   │              │              │
   ▼              ▼              ▼
 download    download what    retry once,
 artifacts   landed + flag    then escalate
 & email     for review
```

Because the result lists every file with a fetchable `s3://` URI, the download/email step
needs no knowledge of the bot — it iterates `artifacts.files`, filtering on the `reports/`
prefix for the documents a human should receive.

## What we need from Praxis

1. **Confirm the trigger point** — is the report run a step in the existing payroll BPMN
   after submit confirmation, or a separate process correlated by `businessKey`? Firing on
   confirmation matters: it is what keeps the FIFO wait at roughly zero.
2. **Accept the `MessageGroupId` rule**, or tell us you would rather we compute it behind a
   dispatcher. This is the one piece of worker-side knowledge the sender has to carry, and
   getting it wrong removes the session guarantee silently rather than loudly.
3. **Size the Receive Task at 30 minutes**, matching the queue visibility timeout — not at
   the 90-second execution time.
4. **Confirm retention.** Bucket lifecycle expires artifacts at 90 days. If these reports are
   compliance evidence, where should they be copied to, and by whom?
5. **Agree the queue name** — `<env>-financeagent-tasks-sicere.fifo` proposed — so it can be
   provisioned per environment. It must be created FIFO; that cannot be changed later.

## Status on our side

Implemented and unit-tested (111 tests in `agent-worker`, 61 in `common-lib`, 35 in
`contract-api`):

- `ccss-sicere-reports` descriptor + adapter, five reports × two formats
- `agent-task-request.v1` / `agent-task-result.v1` schemas and POJOs in `contract-api`,
  published to GitHub Packages
- `AgentTaskListener` — consumes the task queue, dispatches on `botId`, publishes the
  result with S3 locations. Disabled by default until its queue exists.
- Idempotency matching the existing listeners: warm duplicate re-publishes the cached
  result, cold duplicate reports `EXPIRED_DUPLICATE` rather than running the automation
  a second time
- An in-process permit keyed on the same session identity as `MessageGroupId`, so submit
  and report runs for one company cannot overlap even within a single worker

Remaining before a live run: provision the FIFO task queue, then enable the listener
(`AGENT_TASK_ENABLED=true`) — **in that order**. Enabling it against a queue that does not
exist takes the worker's other listeners down with it.
