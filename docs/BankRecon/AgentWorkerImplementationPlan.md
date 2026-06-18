# Bank Statement Upload to Xero — Agent-Worker & Contract Implementation Plan

| Date | Author | Summary |
| ---- | ------ | ------- |
| 2026-06-18 | NeoProc agent team | Initial implementation plan for the agent-worker (Xero UI automation) half + the `contract-api` wire types. Companion to Praxis's `BankStatementUploadDesign.md`. |
| 2026-06-18 | NeoProc agent team | Folded in Q12 volume + lockout decisions: persistent Secrets-Manager+KMS session store, halt-batch-on-lockout (§2.4). Phase 1 contracts landed on branch `feat/bankrecon-xero-upload` (records + schemas + 12 green tests). |

**Scope of this plan:** the NeoProc deliverable — (a) the new wire types/schemas in `contract-api` (we own *all* contracts, same model as payroll; Praxis consumes from GitHub Packages), and (b) the `PORTAL_ID=xero` agent-worker that performs the Xero UI automation. Praxis's §9 build items (Drive client, entities, BPMN, scheduler, HITL) are **out of scope** here except at the contract/queue hand-off points, which are called out explicitly.

**Locked inputs (2026-06-18 planning rounds):**
- We own the contracts in `contract-api`.
- Build/test against a **Xero demo/trial org**.
- MFA = **TOTP seed in Secrets Manager**; the agent computes the rolling 2FA code.
- **Opening balance is supplied by Praxis** in the request → balance checks are real, not `SKIPPED`.
- **Volume:** ~10 bank accounts/night now, growing with onboarding → must scale without login-lockout.
- **Lockout hardening:** build a **persistent Secrets-Manager + KMS session store now** (§2.4) so the Xero session survives container/scale-to-zero restarts; logins stay near-constant regardless of file count.
- **Lockout response:** on detected lockout/rate-limit, **halt the batch and emit a `retryable` failure** for the in-flight file; remaining messages redeliver (SQS visibility + `maxReceiveCount=5` → DLQ). Never hammer Xero into a longer block.

---

## 1. Architectural decision: parallel bank-statement pipeline (not a payroll retrofit)

The existing worker pipeline is payroll-hardwired:
- `PayrollTaskListener` deserializes `PayrollSubmitRequest` and validates `SchemaValidator.SUBMIT_REQUEST`.
- `PortalRunService.run(portalId, PayrollSubmitRequest, bindings)` takes a payroll request as a typed parameter and runs a **scrape → roster reconciliation** flow.
- `AbstractSubmitAdapter` emits `payroll-submit-result.v1` with `RosterDiff` / `Totals`.

Bank-statement upload is a different task *shape*: **a file goes in; `checks[]` + `reconciliation` + `rejectedRows[]` come out.** Bending the payroll envelope types to carry CSV bytes and balance checks would corrupt the payroll contract and the shared submit path.

**Decision:** stand up a **parallel bank-statement pipeline** that reuses the low-level primitives but not the payroll envelope types:

| Reused as-is | New, parallel to payroll |
| ------------ | ------------------------ |
| `PortalEngine` (descriptor step runner) | `BankStatementTaskListener` (∥ `PayrollTaskListener`) |
| `PortalAuthService` (+ new TOTP step) | `BankStatementRunService` or a `runBankStatement(...)` path (∥ `PortalRunService.run`) |
| `S3ArtifactStore`, `RunManifest`, `HarScrubber`, `PortalRateLimiter` | `AbstractBankStatementAdapter` (envelope emit, ∥ `AbstractSubmitAdapter`) |
| `IdempotencyStore`, `SqsRetryablePublisher`, `EnvelopeIo`, `EnvelopeCipher`/KMS | `XeroBankStatementAdapter` (the Xero UI work) |
| `PortalDescriptor` / `PortalDescriptorLoader`, session store | New `flows` value so listener gating works (§5) |

This keeps the payroll path untouched, gives bank-statement its own businessKey/idempotency/result envelope, and matches the framework's standing modular rule (new portal = descriptor + adapter, no special-casing in shared layers).

---

## 1.5 Login-lockout architecture (the volume answer)

Volume starts at ~10 accounts/night and grows. With session reuse + serialized processing, **logins are near-constant per scan window regardless of file count** (10 or 200) — so the scaling risk is not file count but "does session reuse work." Three layers, all reusing existing mechanisms:

1. **Persistent session reuse (primary).** `PortalAuthService.login` already skips `authSteps` and just navigates to `baseUrl` when a saved session is within `session.ttlMinutes`. Today the only `SessionStore` is `LocalEncryptedSessionStore` (`~/.financeagent/sessions/{portalId}.enc`), which is **ephemeral on Fargate**. **Decision: build an AWS Secrets-Manager + KMS `SessionStore` now** (the deferred M6 idea) so the Xero session survives container/scale-to-zero restarts. Set Xero `session.ttlMinutes` to cover a batch (e.g. 240). With serialized processing, file #1 logs in + saves; files #2..N reuse → **~1 login per window**.
   - **Spike-gated (S0.1):** a persisted `storageState` only helps if Xero's auth is cookie/localStorage-based. AutoPlanilla and INS run `ttlMinutes:0` precisely because their JWT lives in `sessionStorage`, which `storageState()` cannot capture. The spike decides *what* we persist (full `storageState` vs. a "trusted device" cookie that suppresses MFA). If neither survives, the **warm long-lived worker** fallback applies (hold the authenticated browser across the batch in one container lifetime).
2. **Serialized + rate-limited (already the default).** One worker per `PORTAL_ID`; `max-concurrent-messages: 1`, `max-messages-per-poll: 1`; descriptor `rateLimit: {maxConcurrent: 1, minIntervalSeconds: <gap>}` enforced by `PortalRateLimiter`. No parallel login storms.
3. **Halt-on-lockout.** The adapter detects lockout/rate-limit/captcha (selectors confirmed in S0.1) and **halts**: emit a `retryable=true` result (`LOGIN_FAILED`/`SESSION_EXPIRED`/`XERO_UNREACHABLE`), `purge()` the stored session if stale, and stop attempting further logins. SQS redelivers after the visibility timeout; `maxReceiveCount=5` → DLQ. Caps login attempts during a block.

---

## 2. Phase 0 — Xero UI spikes against the demo org (de-risk first)

Everything downstream depends on a confirmed Xero selector map. Do this **before** committing adapter code. Output = a captured HTML/screenshot fixture set + a written selector map + answers to the open Xero questions.

| Spike | Goal | Resolves |
| ----- | ---- | -------- |
| S0.1 — Login + TOTP | Drive `login.xero.com` form, submit, satisfy the 2FA prompt with a generated TOTP code; confirm landing page. Capture whether a "trust this device" path can shorten future logins. | Q8 |
| S0.2 — Org switch by tenant UUID | Determine *how* Xero switches org for a multi-tenant login (org menu vs. tenant-scoped URL); confirm the **tenant UUID** is usable as the selector and the demo login can reach a known UUID. | D3, agent step in §8.3.2 |
| S0.3 — Account selection on import screen | Confirm Xero surfaces the **account number / IBAN** on Accounting → Bank accounts and the import entry point so the agent can match `bankAccountNumber` (+`iban`/`currency`). | **Q2** |
| S0.4 — Import a Statement end-to-end | Upload a pre-formatted CSV (Date/Amount/Payee/Description/Reference + header), confirm **no column-mapping prompt**, trigger and observe the **duplicate/overlap warning**, read back **imported line count** and the **opening/closing balance** Xero shows. | **Q6, Q14**, §7.1 checks |

Spikes can run headed locally (`-Dportal.headless=false`) and use `fixture.capture=true` to snapshot post-step HTML for adapter unit tests.

### Phase 0 — RESULTS (2026-06-18, ✅ complete)

Run via the `agent-worker` demo `XeroLoginSpike` against a real Xero **Practice** ("NeoProc") with client orgs (Feuji SRL, MAP SOLUCIONES, Demo Company — matches D3's one-login-all-orgs).

- **Akamai bot block (new).** Xero login sits behind Akamai Bot Manager; a vanilla Playwright Chromium gets a 403 at the edge. **Bypass:** launch installed Chrome (`channel="chrome"`), drop `--enable-automation`, add `--disable-blink-features=AutomationControlled`, mask `navigator.webdriver`. → **The production worker's Playwright launch needs the same stealth config + a real Chrome in the Fargate image** (today it's bundled headless Chromium). New Phase-2/infra requirement.
- **Q8 / session — RESOLVED, store works.** 2FA is **TOTP** with a **"Trust this device"** option. A fresh context seeded only with the persisted `storageState` (incl. the trust cookie) **silently re-authenticates** through Xero's OIDC `form_post` hybrid flow and lands logged-in — confirmed by the no-login reuse probe (`-Dxero.reuseStateFile`). → **The persistent Secrets-Manager + KMS session store is viable; the warm-worker fallback is NOT needed.** Worker re-seeds only when the trust cookie expires (confirm Xero trust-device TTL, ~30 days; detect expiry → alert/HITL). Caveat baked into the probe: wait for the silent SSO to leave `login.xero.com/identity` before judging, else false-negative.
- **Org switch — by SHORT-CODE, not tenant UUID (contract impact).** The switcher uses `OrganisationLogin/!<shortcode>` links (Demo Company = `!0X0!!`); org URL is `go.xero.com/app/!<shortcode>/...`. The tenant UUID is never a UI selector. → **Contract change for Praxis (publish held for this): add optional `xeroShortCode` to `BankStatementTask`**; agent deep-links by short-code, falls back to `xeroOrgName` in the switcher. Keep `xeroOrgUuid`/`xeroOrgName` as API cross-check + label. Supersedes the S0.2 "switch by UUID" assumption.
- **Q2 — RESOLVED.** Account number is rendered on the Bank accounts list → agent matches `bankAccountNumber`. (IBAN not shown for demo accounts — confirm on real CR accounts.)
- **Q6 — refined.** Import is a **3-step wizard: Upload → Import settings → Review** (not single-shot). Stable XUI selectors: file input `input[data-automationid="select-file-control--input"]` (accept incl. `.csv`); stepper `[data-automationid="manual-upload-wizard-stepper"]`; advance `[data-automationid="wizard-next-step-button"]`. With the Xero CSV template, step-2 mapping is auto-filled; a forced mapping choice = `COLUMN_MAPPING_FAILED`. Read-back of imported count at step 3 "Review".

**Net effect on §1.5:** the persistent session store path is confirmed (not the warm-worker fallback). **Security:** the spike's `xero-spike/storageState.json` holds a live session and is gitignored — never commit it.

---

## 3. Phase 1 — Contracts in `contract-api` (parallelizable with Phase 0) — ✅ LANDED 2026-06-18

> Done on `feat/bankrecon-xero-upload`: schemas copied verbatim into `schemas/v1/`, records + enums under `com.neoproc.financialagent.contract.bankstatement`, `SchemaValidator` constants added, 12 tests green (6 fixture + 6 POJO). Publish (step 6) is gated on the Phase-0 spike sign-off so we don't freeze the version before confirming no new envelope field is needed.

Promote the drafts in `docs/BankRecon/contracts/` into the published artifact.

1. **Schemas** → `contract-api/src/main/resources/schemas/v1/bank-statement-upload-request.v1.json` and `-result.v1.json`. Register with the same `$ref` prefix mapping payroll uses (`https://neoproc.com/financialagent/schemas/` → classpath).
2. **Records** in new package `com.neoproc.financialagent.contract.bankstatement`:
   `BankStatementUploadRequest`, `BankStatementUploadResult`, `BankStatementTask`, `FileBody` (+ `S3Ref`), `ResultBody`, `Reconciliation`, `Check`, `RejectedRow`, `ErrorItem`, `Diagnostics`; enums `BankStatementStatus` (SUCCESS/PARTIAL/MISMATCH/FAILED), `ErrorCategory`, `FailedStage`, `CheckName`.
   - **Reuse** `EnvelopeMeta` and `Audit` from the payroll package (don't redefine — the draft `$defs` are inlined only for self-containment).
   - **`Encryption.ciphertextField`**: the payroll record/enum restricts it to `["result"]`; the request side needs `["request"]`. Since we own the contract, keep the Java `Encryption` record shared (a plain `String ciphertextField`) and let the **per-direction JSON-schema enum** enforce the allowed value. No new Java type needed.
3. **Money fields are decimal strings** (`^-?\d+(\.\d+)?$`) — `expectedNetMovement`, `expected/observedOpeningBalance`, `expected/observedClosingBalance`. **Implemented as plain `String`-typed record fields** (not `BigDecimal`), so Jackson always emits strings and the strict pattern can never be tripped by a default numeric serialization — sidesteps the producer trap with no serializer config. The worker computes/compares these as `BigDecimal` internally and stamps the string at the boundary. (Records are `@JsonInclude(NON_NULL)`; no boolean `isX()` getters exist, so `AUTO_DETECT_IS_GETTERS` is moot.) Note: the reused `Audit` record must set **only** `payloadSha256` for bank statements — the schema's `AuditBundle` forbids the payroll manifest/har/screenshot fields.
4. **`SchemaValidator`**: add `BANK_STATEMENT_UPLOAD_REQUEST` and `BANK_STATEMENT_UPLOAD_RESULT`.
5. **Tests:** a `BankStatementSchemaFixtureValidationTest` mirroring `PayrollSchemaFixtureValidationTest` using the four example envelopes as pinned valid fixtures + a few negatives (missing `errorCategory` when `status!=SUCCESS`; money as a JSON number; `additionalProperties`). Plus POJO round-trip tests that **mirror every builder including the error/FAILED path**, with negative tests locking the schema intent.
6. **Publish:** version bump + push to `main` (triggers `publish-contract-api.yml`, redeploys root → common-lib → contract-api). Hand Praxis the version to pin.

**Contract notes that affect both sides:**
- The result schema requires `reconciliation` + `checks` **even on `FAILED`** (nothing imported → `importedLineCount: 0`, empty `checks`). The minimal/failed-result builder must populate a valid `reconciliation`.
- The result `task` block is **echoed verbatim** from the request (identical shape both directions) — it carries the `expected*` values the checks compare against. (Unlike payroll, where echoing the request task into the result failed validation — here it's required and valid.)
- **`businessKey` passthrough is sacred:** the result envelope's `businessKey` and the SQS message attribute must equal the request's, verbatim — Praxis correlates on it.

---

## 4. Phase 2 — Worker scaffolding

| # | Component | Notes |
| - | --------- | ----- |
| 1 | `xero` portal descriptor (`xero.yaml`) | `baseUrl` = Xero login/dashboard; `credentialScope: shared` (single login); `authSteps` = login form + **TOTP step**; `flows: [bankstatement]` (new flow value, §5). Session reuse TTL TBD by S0.1 (verify it survives — storageState misses sessionStorage/JWT). |
| 2 | TOTP auth step | New `PortalEngine`/`PortalAuthService` step type that reads `credentials.totpSeed`, computes the current RFC-6238 code, and types it into the 2FA field. Add a small TOTP generator util (HMAC-SHA1 + base32 via commons-codec; no heavy dep). Unit-test against RFC 6238 vectors. |
| 3 | `BankStatementTaskListener` (`@SqsListener("${agent.worker.bankstatement-task-queue}")`) | ∥ `PayrollTaskListener`: schema-validate raw bytes → deserialize `BankStatementUploadRequest` → idempotency (warm-cache re-publish / cold-cache `EXPIRED_DUPLICATE`) → run → publish `BankStatementUploadResult` to results queue, echoing `businessKey`. Gated by a new `@ConditionalOnProperty(agent.worker.bankstatement-enabled)`. |
| 4 | `BankStatementRunService` (or `PortalRunService.runBankStatement`) | ∥ `run(...)`: build credential + `params.*` bindings, decrypt the `request` body (KMS when `encryption` present, cleartext in dev), **write the CSV bytes to the run dir**, set a binding pointing at it, run descriptor steps + the Xero adapter, persist manifest + S3 upload. Reuses PortalEngine/PortalAuthService/RunManifest/S3ArtifactStore. |
| 5 | `AbstractBankStatementAdapter` | ∥ `AbstractSubmitAdapter`: owns `bank-statement-upload-result.v1` emission + metrics; subclass returns status + `ResultBody` (checks/reconciliation/rejectedRows/diagnostics). |
| 6 | `XeroBankStatementAdapter` | The Xero UI work (§8.3 of the design): org switch, account select, Import a Statement, duplicate handling, read-back, build `ResultBody`. |
| 7 | Adapter/flow registry | Extend `PortalRunService.newAdapter` (or a parallel bank-statement registry) with `case "xero" -> new XeroBankStatementAdapter()`. |
| 8 | SQS config | Add `bankStatementTaskQueueName` / `bankStatementResultsQueueName` (+ dlq) to the SQS config, mapped to `agent.worker.bankstatement-task-queue` / `-results-queue`. Names from design Q4: `{prefix}-financeagent-tasks-bankstatement-xero`, `{prefix}-financeagent-bankstatement-results`, `{prefix}-financeagent-bankstatement-dlq`. |
| 9 | Listener gating | Extend `PortalDescriptor` flows + `PortalFlowsEnvironmentPostProcessor` so a `flows:[bankstatement]` descriptor enables **only** the bankstatement listener (and payroll descriptors do **not** register it). Prevents the submit-only-zombie / missing-queue context-abort class of bug. |
| 10 | **AWS Secrets-Manager + KMS `SessionStore`** (§1.5) | New `SessionStore` impl in `common-lib` alongside `LocalEncryptedSessionStore` (interface `load/save/purge` already exists). Persists the Xero session across Fargate/scale-to-zero restarts so logins stay near-constant. `PortalAuthService`/`runBankStatement` select it when `FINANCEAGENT_CREDENTIALS=aws`; local dev keeps the encrypted-file store. *What* it persists (full `storageState` vs. trusted-device cookie) is set by S0.1. |

---

## 5. Phase 3 — Xero adapter implementation

Build `XeroBankStatementAdapter` from the Phase 0 fixtures, implementing the §8.3 behaviour spec:

1. Fetch the shared Xero credential + TOTP seed from Secrets Manager; log in; satisfy MFA.
2. **Switch org by `task.xeroOrgUuid`** (UUID authoritative, `xeroOrgName` fallback). Fail `ORG_NOT_FOUND` if unreachable → `ORG_SELECTED` check.
3. Open the bank account matching `bankAccountNumber` (disambiguate with `iban`/`currency`); **record pre-import balance**. Fail `ACCOUNT_NOT_FOUND` → `ACCOUNT_SELECTED` check.
4. **Import a Statement**, upload the CSV (`setInputFiles`). No column mapping; an unexpected mapping prompt → `COLUMN_MAPPING_FAILED`. → `FILE_ACCEPTED` check.
5. On a **duplicate/overlap warning**, do **not** force — record `NO_DUPLICATE` failed → `status=MISMATCH`, `errorCategory=DUPLICATE_STATEMENT`.
6. Read back `importedLineCount`, opening/closing balance, rejected rows. Build `checks[]` (one per applicable check), `reconciliation` (compute `observedNetMovement` from the balance delta / imported lines; `expectedOpeningBalance` from Praxis, `observedOpeningBalance` from Xero), and derive `status` per §7.2 precedence (FAILED > MISMATCH > PARTIAL > SUCCESS).
7. Populate `diagnostics` (workerRunId, attemptCount, durationMs, xeroUrl, xeroMessage, `logRef`) and capture **screenshots** (confirmation + any warning) → `screenshotRefs[]` in S3. Set `errorCategory` (primary), `errors[]` (all), `failedStage`, `retryable` (true only for `XERO_UNREACHABLE`/`SESSION_EXPIRED`/`TIMEOUT`).
8. **Halt-on-lockout (§1.5):** if login/2FA surfaces a lockout / rate-limit / captcha signal (selectors from S0.1), stop retrying logins, `purge()` a stale session, and emit a `retryable=true` `LOGIN_FAILED`/`SESSION_EXPIRED` result with `failedStage=AUTH` so SQS redelivery (not a tight in-process loop) handles the backoff.

**Idempotency caveat:** a Xero statement import is **not** idempotent — a re-upload creates a duplicate statement. The `EXPIRED_DUPLICATE` cold-cache path (receiveCount>1, no cached result → synthetic FAILED, no re-execution) is therefore important, and Xero's own duplicate warning is the second safety net.

---

## 6. Phase 4 — Integration & verification

1. **Local cleartext e2e:** drive a request envelope (encryption off) through `BankStatementTaskListener` against the demo org; assert a schema-valid result envelope + correct `checks[]`/status on success, count-short, and duplicate cases.
2. **Dev-queue e2e with Praxis** (mirror the prior SQS dev-parity validation): real dev queues, Praxis publishes a request, worker uploads to the demo org, Praxis correlates by businessKey and routes archive vs. HITL. Validate the HITL bundle renders (checks table, screenshots, CSV download link).
3. **KMS on:** flip `encryption.enabled`, confirm the worker decrypts the `request` body and Praxis decrypts the `result`.

---

## 7. Phase 5 — Infra & prod readiness

| Item | Notes |
| ---- | ----- |
| SQS queues | task / results / dlq per Q4 (Praxis provisions; worker points at them). Confirm names + redrive policy. |
| Secrets Manager | One secret for the shared Xero login **+ TOTP seed** (propose `xero/ui-login`, shape `{username, password, totpSeed}`). **Hand Praxis the final secret name** (closes Q8 on their side). |
| Fargate `xero` worker service | New per-portal service, ARM64/Graviton image, with the calendar scale-to-zero window pattern. |
| Volume sizing (Q12) | **Input still needed:** files/accounts/orgs per night → SQS concurrency, Xero login rate, auto-login lockout risk. Pending your numbers. |

---

## 8. Open items / inputs still needed

- **Q12 volume** — nightly file/account/org counts for concurrency + lockout sizing.
- **S0.2 org-switch mechanism** — exact Xero mechanism (URL vs. menu); resolved by the spike.
- **Final Xero secret name** — to hand Praxis (proposed `xero/ui-login`).
- **Session-reuse viability** — does a trusted Xero session survive storageState reuse, or must every run re-auth + TOTP? (S0.1.)
- **Priority/sequencing** relative to the existing CR payroll portals.

## 9. Suggested sequencing

Phase 0 (spikes) **‖** Phase 1 (contracts) run in parallel → Phase 2 (scaffolding) → Phase 3 (adapter, needs Phase 0 fixtures) → Phase 4 (integration with Praxis) → Phase 5 (infra/prod). Contracts publish early so Praxis can build their §9 against the real artifact.
