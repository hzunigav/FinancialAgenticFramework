# BankRecon — Note to Praxis (agent-worker status + contract)

_From: NeoProc agent-worker. Date: 2026-06-19._

The Xero bank-statement upload adapter is **built and validated live end-to-end** against
the Xero demo org (cold login, warm-session reuse, a clean import, and the duplicate path
all proven). This note covers what you consume, the two design points we resolved on the
live UI, and what the agent needs from your side.

---

## 1. Contract (you consume from GitHub Packages)

Artifact `com.neoproc.financialagent:contract-api` (transitively `common-lib`),
version **`1.0.0-SNAPSHOT`** — already published to `main`. Schemas:

- `schemas/v1/bank-statement-upload-request.v1.json`
- `schemas/v1/bank-statement-upload-result.v1.json`

Java POJOs live in `com.neoproc.financialagent.contract.bankstatement.*`
(`BankStatementUploadRequest` / `…Result`, `BankStatementTask`, `FileBody`, `ResultBody`,
`Reconciliation`, `Check`, `ErrorItem`, enums).

### Request — fields the agent relies on
- **`task.xeroShortCode`** — **REQUIRED**. The org selector, e.g. `!0X0!!`. The agent
  deep-links `go.xero.com/app/<shortCode>/manage-bank-accounts`. **Not** the tenant UUID —
  Xero's UI never selects an org by UUID.
- `task.xeroOrgUuid` / `task.xeroOrgName` — optional (cross-check + human label only).
- `task.bankAccountNumber` — REQUIRED; the agent matches the account widget by this string
  (e.g. `090-8007-006543`).
- `task.currency`, `task.periodStart`, `task.periodEnd`, `task.expectedRowCount`,
  `task.expectedNetMovement` — used to build the reconciliation `checks[]`.
- `task.expectedOpeningBalance` / `…ClosingBalance` — **optional**. If omitted, the balance
  checks are reported as **skipped** (omitted from `checks[]`), never failed. Supply them if
  you want real balance verification.
- **The CSV** rides in `request` as a `FileBody` (`file.inline` base64, with `sha256`), or a
  KMS ciphertext string when `encryption` is set. Money fields are **decimal strings**, not
  JSON numbers.

### Result — what you'll see back
- **`businessKey` is echoed verbatim** from your request (envelope JSON _and_ the SQS message
  attribute). We never synthesize it — correlate on it.
- `result.status` ∈ `SUCCESS | PARTIAL | MISMATCH | FAILED`.
- `result.errorCategory` (when not SUCCESS), `result.failedStage`, `result.retryable`.
- `result.reconciliation` (expected vs. observed: row count, net movement, balances, date range).
- `result.checks[]` — `ORG_SELECTED`, `ACCOUNT_SELECTED`, `FILE_ACCEPTED`, `ROW_COUNT`,
  `NO_DUPLICATE`, and the balance checks; each with `passed` + expected/observed.
- `audit.payloadSha256` only (we do not echo the file back).

---

## 2. Two design points we resolved on the live UI

### GAP 1 — Column mapping is **mandatory** and keyed to the **exact CSV header strings**
Xero's "Map and assign columns" step always exists, and Xero **remembers the mapping per
org keyed to the header names**. Per our agreement, **the agent does not touch the mapping** —
but that only works if the CSV headers match what the org was first mapped with, **byte-for-byte**.

On the live run a header of `Amount` against an org mapped with `*Amount` triggered a blocking
**"Invalid column assignment(s)"** modal and reset the mapping. The fix is upstream, not in the
agent:

> **Praxis contract:** the CSV header line must match Xero's per-org template verbatim. For the
> demo org that is:
>
> ```
> *Date,*Amount,Payee,Description,Reference,Check Number
> ```
>
> (`*` = Xero's required-column convention; dates `M/d/yyyy`; a single signed amount column.)
> The canonical sample we validated against is `docs/BankRecon/sample/DemoCoBank.csv`.

**Onboarding step per new org:** map the columns once by hand on the first import (or confirm
the org already has the mapping). After that Xero auto-applies it and the agent sails through
with no modal. If you'd prefer the agent to actively set the mapping instead (so header drift
self-heals), say so — it's a deliberate scope choice we left out on your guidance.

### GAP 2 — Duplicates are **never prompted**; reported after the fact
Contrary to the original design (D7/Q5), Xero does **not** show a duplicate prompt to accept/reject:

- **Per-line duplicates** are silently suppressed and reported in a post-import banner on the
  reconcile page: `"N statement lines were imported. M were duplicates."` The agent parses that
  for the authoritative imported/duplicate counts.
- **A full-overlap re-import** (every line duplicates an already-imported statement — e.g. an
  SQS redelivery / our idempotency cold path) makes Xero **disable "Complete import"** and land
  on the reconcile page on its own. The agent treats this as completed and reads the banner.

**Result mapping:** any duplicates → `status = MISMATCH`, `errorCategory = DUPLICATE_STATEMENT`,
with `NO_DUPLICATE` (and `ROW_COUNT` when imported < expected) failing. **This is a HITL signal,
not a hard error** — a human decides whether the overlap was expected. Please route
`DUPLICATE_STATEMENT` to review, not to the DLQ.

**Idempotency caveat:** a Xero import is **not** idempotent (re-upload → duplicates). Our
cold-cache path emits a synthetic FAILED without re-executing, and Xero's banner is the second
safety net — but please avoid blind redelivery of an already-succeeded `businessKey`.

---

## 3. Queues & secrets (for provisioning alignment)

- Task queue: `<prefix>-financeagent-tasks-bankstatement-xero`
- Results queue: `<prefix>-financeagent-bankstatement-results`
- DLQ: the shared `<prefix>-financeagent-dlq` (`maxReceiveCount=5`)
- Shared Xero login secret (one Practice login fronts all client orgs):
  **`<prefix>/financeagent/shared/portals/xero`** — a JSON map
  `{username, password, totpSeed}` (the path the worker's shared-scope credential
  provider resolves to).
- Session secret: **`<prefix>/financeagent/sessions/xero`** — managed by the worker
  (persisted Playwright session; survives scale-to-zero). NeoProc-side only.

---

## 4. Deployment note (resolved; not part of the contract)
Xero's login is behind Akamai Bot Manager. We confirmed (2026-06-20, probing inside the actual
Fargate runtime image) that our stealth launch with the image's **bundled Chromium clears Akamai
headless** — no branded-Chrome layer, no xvfb, stays on ARM64. The worker logs in per cold batch
and reuses the session otherwise. Nothing here affects the contract you consume.
