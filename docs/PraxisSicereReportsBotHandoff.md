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
Concurrency is capped at 1 per client. Size the BPMN task timeout at **5 minutes** to leave
room for a slow Planilla PDF (the largest, ~370KB).

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

**One request queue, one result queue, a fixed envelope, and a `botId` that says what to
run.** Adding a bot then becomes a worker-side change with **no contract change and no
republish** — which is the property that actually makes this scale.

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

### Trade-offs, stated plainly

| | Today (per-bot queues) | Proposed (one queue) |
|---|---|---|
| Add a bot | 2 queues + 2 schemas + republish + deployment | descriptor + adapter, worker-side only |
| Isolation | A stuck bot blocks only its own queue | A stuck bot occupies shared consumers |
| Autoscaling | Per-queue depth | Aggregate depth |
| Param validation | Central, published schema | Descriptor-declared, validated in-worker |

The isolation loss is the one that deserves scrutiny. Two mitigations, and neither requires
going back to per-bot queues: per-bot concurrency limits already exist
(`rateLimit.maxConcurrent` in each descriptor — CCSS is pinned to 1 regardless of how many
workers run), and lanes can be split by *expected duration* (`-fast` / `-slow`) rather than
by bot, which caps the blast radius at two queues no matter how many automations you add.

Per-portal autoscaling matters less than it appears: CCSS is capped at one concurrent run by
the portal itself, so extra workers for it were never useful.

### Migration — incremental, nothing breaks

1. **Add** the generic queue and envelope alongside what exists. `ccss-sicere-reports` is the
   first consumer. Nothing currently running changes.
2. **Migrate** payroll capture/submit when convenient, running old and new listeners in
   parallel until Praxis switches.
3. **Retire** the per-portal task queues and the divergent bank-statement pair.

Step 1 is enough to launch this bot and to onboard the next several automations.

---

## BPMN sketch

```
[Payroll submit confirmed]
          │
          ▼
  ┌───────────────────────────────┐
  │ Service Task: archive reports │  send agent-task-request.v1 to
  │  botId = ccss-sicere-reports  │  <env>-financeagent-tasks
  │  params: clientIdentifier,    │
  │          period, formats      │
  └───────────────────────────────┘
          │
          ▼
  ┌───────────────────────────────┐
  │ Receive Task (5 min timeout)  │  correlate on businessKey
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
   after submit confirmation, or a separate process correlated by `businessKey`?
2. **Confirm retention.** Bucket lifecycle expires artifacts at 90 days. If these reports are
   compliance evidence, where should they be copied to, and by whom?
3. **Agree the queue name** for the shared task queue (`<env>-financeagent-tasks` proposed)
   so it can be provisioned in each environment.

## Status on our side

Implemented and unit-tested (103 tests in `agent-worker`, 35 in `contract-api`):

- `ccss-sicere-reports` descriptor + adapter, five reports × two formats
- `agent-task-request.v1` / `agent-task-result.v1` schemas and POJOs in `contract-api`
- `AgentTaskListener` — consumes the shared queue, dispatches on `botId`, publishes the
  result with S3 locations
- Idempotency matching the existing listeners: warm duplicate re-publishes the cached
  result, cold duplicate reports `EXPIRED_DUPLICATE` rather than running the automation
  a second time

Remaining before a live run: provision the shared task queue, and push `contract-api` to
`main` so the new schemas publish to GitHub Packages for Praxis to consume.
