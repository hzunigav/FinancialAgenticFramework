# Bank Statement Upload — Contract Drafts (for the agent team)

**Status:** DRAFT for review. These files are staged under `docs/contracts/` so they can be handed to the NeoProc finance agent-worker team. They are **not** wired into the build yet — the canonical home is the `contract-api` artifact (GitHub Packages `com.neoproc.financialagent:contract-api`, repo `hzunigav/FinancialAgentFramework`). See `../BankStatementUploadDesign.md` for the full design.

## Files

| File | Purpose |
| ---- | ------- |
| `bank-statement-upload-request.v1.json` | JSON Schema (Draft 2020-12). Praxis → agent: upload one CSV into a specific Xero org. |
| `bank-statement-upload-result.v1.json` | JSON Schema (Draft 2020-12). Agent → Praxis: outcome of the upload. |
| `examples/request.cleartext.json` | Valid request, cleartext body (local-dev / encryption off). |
| `examples/request.encrypted.json` | Valid request, KMS-encrypted body (`vault:v1:` ciphertext + `encryption` block). |
| `examples/result.success.json` | Valid SUCCESS result (count matches). |
| `examples/result.failed.json` | Valid non-success result (MISMATCH / duplicate statement). |

## Wire shape (recap)

- `envelope` — `EnvelopeMeta` (envelopeId UUID, businessKey, firmId, locale, createdAt, issuer, issuerRunId).
- `task` — **cleartext** routing metadata. The org selector is **`xeroShortCode`** (required, e.g. `"!0X0!!"`) — the deterministic value the Xero UI switches on; the agent deep-links `go.xero.com/app/<shortCode>/…`. `xeroOrgName` is the org-switcher fallback + label; **`xeroOrgUuid` is optional** (API cross-check only — it is *not* a UI selector). Confirmed by the Phase-0 spike against a real Xero practice. The agent matches the account by `bankAccountNumber` (+ `iban`/`currency`).
- `encryption` — `null` for cleartext, or `{ scheme: "kms-envelope-v1", keyName, keyVersion, ciphertextField }`.
- `request` / `result` — the **body**. Cleartext = an object; encrypted = a `vault:vN:` string. `ciphertextField` names which field holds the ciphertext.
- `audit.payloadSha256` — SHA-256 (lowercase hex) of the canonical JSON of the cleartext body.

## Verification & error reporting (result body)

The result is **not** a single boolean. The agent reports, and Praxis re-asserts, a set of independent checks so a human can troubleshoot from the HITL task alone:

- **`reconciliation`** — `expectedRowCount`/`importedLineCount`, `expected`/`observedNetMovement`, `expected`/`observedOpeningBalance`, `expected`/`observedClosingBalance` (all monetary values are **decimal strings**), plus `currency` and `statementDateRange`.
- **`checks[]`** — one `{name, passed, expected, observed, detail}` per check: `ORG_SELECTED`, `ACCOUNT_SELECTED`, `FILE_ACCEPTED`, `NO_DUPLICATE`, `ROW_COUNT`, `NET_MOVEMENT`, `OPENING_BALANCE`, `CLOSING_BALANCE`. Skipped checks (balances when the expected value was null) are **omitted**.
- **`rejectedRows[]`** — `{rowNumber, rawLine, reason}` for rows Xero skipped (e.g. partial import).
- **`errors[]`** — every problem found; `errorCategory` is the primary/most-severe of these.
- **`status`** — `SUCCESS` | `PARTIAL` (incomplete) | `MISMATCH` (reconciliation failed) | `FAILED` (nothing imported). See `../BankStatementUploadDesign.md` §7.2 for derivation.
- **`retryable`** — `true` only for transient failures (`XERO_UNREACHABLE`, `SESSION_EXPIRED`, `TIMEOUT`); reconciliation failures are always `false`.
- **`failedStage`** — `AUTH | ORG_SELECT | ACCOUNT_SELECT | UPLOAD | VERIFY | null`.
- **`diagnostics`** — `workerRunId`, `attemptCount`, `durationMs`, `xeroUrl`, `xeroMessage`, `screenshotRefs[]`, `logRef` (CloudWatch). These power the HITL evidence panel.

`errorCategory`/`errors[].category` enum is grouped: **access** (`XERO_UNREACHABLE`, `LOGIN_FAILED`, `MFA_REQUIRED`, `SESSION_EXPIRED`, `ORG_NOT_FOUND`, `ACCOUNT_NOT_FOUND`), **file** (`EMPTY_FILE`, `FILE_CORRUPT`, `UNSUPPORTED_FORMAT`, `COLUMN_MAPPING_FAILED`), **upload** (`UPLOAD_REJECTED`, `DUPLICATE_STATEMENT`, `PARTIAL_IMPORT`), **reconciliation** (`COUNT_MISMATCH`, `NET_MOVEMENT_MISMATCH`, `OPENING_BALANCE_MISMATCH`, `CLOSING_BALANCE_MISMATCH`), **other** (`TIMEOUT`, `UNKNOWN`).

## Conventions inherited from the payroll contract

1. **`additionalProperties: false` everywhere.** Both ends validate strictly. Do not emit fields not in the schema. (Watch Jackson serializing boolean `isX()` getters as extra properties — it bit the payroll `RosterDiff.isEmpty()`; disable `MapperFeature.AUTO_DETECT_IS_GETTERS` or rename.)
2. **Dates/times as strings, not numbers.** `createdAt` is RFC 3339 `date-time`; `periodStart`/`periodEnd` are `date`. Serialize with `WRITE_DATES_AS_TIMESTAMPS=false` (Instants must be ISO strings, LocalDates must be `YYYY-MM-DD`, not `[y,m,d]` arrays).
3. **Encryption wire prefix is `vault:v1:`** (regex `^vault:v\d+:`) even though the scheme name is `kms-envelope-v1` — kept for backward-compat with the existing validator regex.
4. **`expectedRowCount` vs `importedLineCount`** — both count **data rows excluding the header** (the CSV is pre-formatted to Date/Amount/Payee/Description/Reference + header). Praxis asserts `importedLineCount == expectedRowCount`; a mismatch → HITL.
5. **`errorCategory` is required (non-null) whenever `status != SUCCESS`** (enforced by the `if/then/else` in the result schema).
6. **Monetary values are decimal strings** (`"1279.50"`), never JSON numbers — avoids float drift and matches payroll `BigDecimal`-as-string. Pattern `^-?\d+(\.\d+)?$`.

## Integration checklist (agent side)

1. Add Java records under a new package, e.g. `com.neoproc.financialagent.contract.bankstatement`:
   `BankStatementTask`, `FileBody` (+ `FileRef`), `ResultBody`, `BankStatementUploadRequest`, `BankStatementUploadResult` — reusing the shared `EnvelopeMeta`, `Encryption`, `AuditBundle` records (do **not** re-define them; the `$defs` here are inlined only to make the drafts self-contained).
2. Drop the two schemas under `contract-api/src/main/resources/schemas/v1/` and register them with the same `JsonSchemaFactory` `$ref` prefix mapping payroll uses (`https://neoproc.com/financialagent/schemas/` → classpath).
3. Add the schema names to `SchemaValidator` (e.g. `BANK_STATEMENT_UPLOAD_REQUEST`, `BANK_STATEMENT_UPLOAD_RESULT`).
4. Bump the artifact version and publish to GitHub Packages; Praxis updates `${financialagent-contract-api.version}` to match.
5. Add a `SchemaFixtureValidationTest` mirroring the payroll one, using the example files here as pinned valid fixtures (plus a couple of invalid ones).

## Open items the agent team owns

- **Q2** — confirm the agent can read the account number / IBAN on Xero's import screen to select the account.
- **Q8** — shared Xero UI login: Secrets Manager secret name/shape + MFA strategy (TOTP / trusted session).
- **Q12** — nightly volume → SQS concurrency, Xero login rate, lockout risk.
- **Queues** — confirm a `PORTAL_ID=xero` worker consumes `<prefix>-financeagent-tasks-bankstatement-xero` and publishes to `<prefix>-financeagent-bankstatement-results`.
- **`ciphertextField` for submit-style bodies** — for this flow it is only ever `"request"` (request schema) / `"result"` (result schema). Confirm the shared `Encryption` enum permits both values (the payroll contract currently restricts it to `["result"]` — flag if reused verbatim).
