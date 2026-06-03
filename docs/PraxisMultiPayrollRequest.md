# Request to Praxis — enable multi-planilla (consolidated) capture for AutoPlanilla

**Status:** proposed · **Owner:** NeoProc (agent-worker) · **Consumer:** Praxis BPM
**Created:** 2026-06-03

## Goal

Let a single AutoPlanilla **capture** run pull **one consolidated CCSS report built from
several payrolls (planillas)**, instead of one planilla per run. AutoPlanilla already
supports selecting multiple planillas on the *Reporte CCSS* page and consolidates them
server-side into one report (summed totals, merged employee rows). We want Praxis to drive
that selection so the consolidated roster flows into a single downstream CCSS /
INS RT-Virtual submit.

## What changes in the contract (our side first, then yours)

**1. We (framework) add `task.planillas[]`** to the shared `task` block in
`contract-api/.../schemas/v1/payroll-capture-result.v1.json` (the block that
`payroll-capture-request.v1` references) and **republish `contract-api`
(`1.0.0-SNAPSHOT`)** to GitHub Packages. The `task` object is `additionalProperties: false`,
so Praxis **cannot** send the new field until this is published — we'll confirm when it's live.

New field, added alongside the existing singular `planilla`:

```jsonc
"planillas": {
  "type": "array",
  "minItems": 1,
  "items": {
    "type": "object",
    "required": ["id"],
    "additionalProperties": false,
    "properties": {
      "id":   { "type": "string", "minLength": 1 },  // AutoPlanilla payroll id (the data-value, e.g. "1051")
      "name": { "type": "string" }                    // display label — audit/log only, optional
    }
  }
}
```

**2. Praxis sends `task.planillas`** (1..N) for AutoPlanilla capture once the new
`contract-api` is consumed.

### Critical: select by **id**, not by name

Each `planillas[].id` **must be the stable AutoPlanilla payroll id** — the same internal id
you already send today as `task.planilla.id` (e.g. `1051`). The worker selects each planilla
by `data-value="<id>"` on the multi-select. **Do not** rely on the display label — operators
rename labels, and the worker never matches on them. `name` is optional, log/manifest only.

### businessKey (consolidated)

With N planillas → 1 capture, a per-planilla key is ambiguous. Use:

```
businessKey = "<period>::<cédula jurídica>"     e.g.  "2026-05::3-101-578509"
```

i.e. key on the **reporting entity (cédula jurídica)**, not on any single planilla. The
worker echoes `businessKey` **verbatim** on the result envelope (BPM correlation depends on
this — no hop may rewrite it). This is the same cédula you will use as `clientIdentifier` on
the downstream CCSS / INS submit tasks.

## What stays the same

- `task.period` (`from`/`to`), `envelope` fields, encryption, and the result body schema are
  unchanged.
- **Single-planilla flows need no change** — the worker keeps accepting the existing singular
  `task.planilla` (treated internally as a 1-element list). Migrate to `planillas` only for
  consolidated runs. If both are present, `planillas` wins.
- **Currency:** the report consolidates in CRC (AutoPlanilla converts USD→CRC via the
  exchange-rate column), so mixing USD and CRC planillas is fine — we capture the CRC
  gross/renta columns. CCSS / INS file in CRC.

## Please confirm (domain questions only Praxis / the operator can answer)

1. **One filing entity per consolidation.** All planillas selected in a single capture must
   belong to **one cédula jurídica** and map to **one** downstream CCSS planilla / INS RT
   policy. Consolidating planillas from different filing entities would corrupt the submit
   mapping.
2. **No employee double-count.** Confirm the same cédula (employee) does **not** appear in
   two selected planillas — AutoPlanilla's consolidated report would list them twice,
   inflating the roster and totals.

## Example — consolidated capture request (after the change)

```json
{
  "schema": "payroll-capture-request.v1",
  "envelope": {
    "envelopeId": "…uuid…",
    "businessKey": "2026-05::3-101-578509",
    "firmId": 1001,
    "locale": "es",
    "createdAt": "2026-06-03T12:00:00Z",
    "issuer": "praxis-bpm",
    "issuerRunId": "…"
  },
  "task": {
    "type": "PAYROLL_CAPTURE",
    "operation": "CAPTURE",
    "sourcePortal": "autoplanilla",
    "period": { "from": "2026-05-01", "to": "2026-05-31" },
    "planillas": [
      { "id": "1051", "name": "FEUJI Costa Rica USD" },
      { "id": "1052", "name": "FEUJI Costa Rica CRC" }
    ]
  }
}
```

## Sequencing

1. We add `planillas[]` to the schema + POJO, wire the worker (multi-select + paginated
   capture across pages), and republish `contract-api` → we notify Praxis.
2. Praxis rebuilds against the new `contract-api`, then emits `task.planillas` for
   consolidated AutoPlanilla captures.
3. Single-planilla flows continue on `task.planilla` unchanged.

> Note: consolidated reports routinely exceed AutoPlanilla's 100-rows-per-page limit
> (e.g. FEUJI USD+CRC = 101 employees). The worker's row-pagination change captures every
> page; both land together so per-row capture is complete for large consolidations.
