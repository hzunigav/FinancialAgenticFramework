# Portal Onboarding

How to add a new portal to the framework — from *"here's a URL I want to scrape"* to *"the agent is pulling data from it **on my local machine**."*

**Before recording selectors:** read [WorkerActionTypes.md](WorkerActionTypes.md) first to identify whether you're building an Input (capture), Output (submit), or Analysis action — the action type determines which base class, descriptor shape, and registry entry the portal needs. Come back here once you know what you're building.

**Scope:** this doc covers local descriptor development. Once the descriptor runs cleanly against the real portal from a developer's workstation, production rollout (worker image build, Vault credential provisioning, BPMN wiring, staging rehearsal) is covered separately in [PortalDeployment.md](PortalDeployment.md).

The manual work is a one-shot recording session on your machine using Playwright Codegen. Everything after that is small iteration between you and the agent. This guide is reusable across portals; a worked example (AutoPlanilla) is at the bottom.

---

## Before you start — tell me in chat

Before touching a keyboard, paste these four things in the conversation so we're aligned on scope:

1. **Portal login URL** — e.g. `https://app.example.com/login`.
2. **MFA?** — none / SMS / email / TOTP / hardware key. Dictates whether the descriptor needs a `pause` step.
3. **What to capture** — which page, which values. A screenshot of the target page with a few arrows on it is ideal; a prose description works.
4. **A short id for the portal** — lowercase, no spaces. Used as filenames: `portals/<id>.yaml`, `<Id>Adapter.java`, secrets key `portals.<id>.credentials.*`.

I'll write a descriptor skeleton + adapter from those, and then you do steps 1–4 below.

---

## 1 — One-time machine setup

Skip this section if you've already done it for a previous portal.

Install Node.js (needed for Playwright Codegen only):

```
winget install OpenJS.NodeJS.LTS
```

or download the LTS installer from [nodejs.org](https://nodejs.org).

Install Chromium for Playwright:

```
npx playwright install chromium
```

`npx` downloads Playwright on demand — no global `npm install` needed.

---

## 2 — Per portal: put credentials where the agent reads them

Edit `C:\Users\<you>\.financeagent\secrets.properties` and append:

```
portals.<portalId>.credentials.username=<your login>
portals.<portalId>.credentials.password=<your password>
```

Other credential fields (tenant id, API key, TOTP seed, whatever) follow the same pattern: `portals.<portalId>.credentials.<fieldname>=<value>`.

Lock down the file (once per file; no need to re-run after future appends):

```
icacls %USERPROFILE%\.financeagent\secrets.properties /inheritance:r /grant:r %USERNAME%:F
```

Credentials never leave your machine and never go through this chat.

---

## 3 — Per portal: record the flow

In any terminal:

```
npx playwright codegen https://<portal-login-url>
```

Two windows open — a Chromium at the login page, and a Playwright Inspector pane showing live-generated code.

In the browser, do the flow end-to-end:

- Log in with your real credentials.
- Navigate through any menus needed to reach the target page.
- Perform any filters, dropdown selections, date pickers, button clicks needed to make the data appear.
- **Wait until the real data is on screen.**
- Stop the recording (close the browser window, or click the red Record toggle in the Inspector).

Copy the full generated code from the Inspector pane.

---

## 4 — Hand off the recording (with one redaction)

Paste the recorded code into chat, **with the password replaced by the literal string `<PASSWORD>`**. Playwright Codegen records password fields as plaintext — it does not mask them. Find any line like:

```java
page.locator("input[type='password']").fill("mySecretPassword123");
```

and make it:

```java
page.locator("input[type='password']").fill("<PASSWORD>");
```

Username can stay or go — not load-bearing. TOTP seeds, API keys, anything else that looked secret when you typed it: redact the same way.

---

## 5 — What I do with your recording

1. Translate the recorded selectors into the `TODO`-marked slots in `agent-worker/src/main/resources/portals/<portalId>.yaml`.
2. If codegen didn't cover the **scrape targets** (codegen records clicks/fills/selects, not reads), I'll ask for the outerHTML of the specific values you want captured, via your browser's DevTools → Elements → right-click → Copy → Copy outerHTML. This is usually 2–3 elements (e.g. a totals row cell, a pagination footer). Paste them in chat.
3. I finish the descriptor and the domain-mapping adapter.
4. You run the agent against the real portal with whatever runtime parameters the descriptor needs:
   ```
   mvn -pl agent-worker exec:java \
     -Dportal.id=<portalId> \
     -Dparams.<key>=<value> \
     -Dparams.<key>=<value>
   ```
5. If a selector misses on the first run, we iterate — by then the run produced its own `trace.zip` and DOM snapshots, so subsequent rounds don't need another full codegen session.

---

## Descriptor design pitfalls

These three traps cost real time on AutoPlanilla; documenting them so the next portal doesn't repeat the lesson.

### Use the listener-supplied bindings — don't invent new names

When the worker runs broker-driven (Praxis publishes a capture/submit envelope), the listener auto-populates a fixed set of `params.*` bindings from the envelope. The descriptor and the adapter must read those names exactly — making up a new name (e.g. `params.fechaInicio` because the portal is in Spanish) produces `IllegalStateException: Unresolved portal binding` at the first step that references it.

| Binding                  | Source                                | Available on            |
|--------------------------|---------------------------------------|-------------------------|
| `params.firmId`          | `envelope.firmId`                     | capture + submit        |
| `params.businessKey`     | `envelope.businessKey`                | capture + submit        |
| `params.issuerRunId`     | `envelope.issuerRunId`                | capture + submit        |
| `params.sourcePortal`    | `task.sourcePortal`                   | capture                 |
| `params.from`            | `task.period.from` (ISO date)         | capture + submit        |
| `params.to`              | `task.period.to` (ISO date)           | capture + submit        |
| `params.planillaId`      | `task.planilla.id`                    | capture + submit        |
| `params.planillaName`    | `task.planilla.name`                  | capture + submit        |
| `params.clientIdentifier`| `task.clientIdentifier`               | submit                  |
| `params.source.submitRequest` | full submit envelope path (file)| submit (set by `PortalRunService`) |

If a portal needs a value that isn't in this list, raise it before authoring — either the contract envelope grows a field, or you add the binding in the listener with a clear name. The CLI dev mode (`mvn exec:java -Dparams.<key>=<value>`) accepts arbitrary names, but those names then become a contract the broker-driven path has to honour. Pick something that maps cleanly back to a contract field.

### Prefer `match: value` over label for stable dropdowns

The `select` action defaults to `match: label`, which calls `selectOption(SelectOption.setLabel(...))` — the visible option text must match exactly. That's fragile for any portal where operators can rename items in the UI: a planilla called "Quincenal USD" today might be "Quincenal Dolares" tomorrow.

When the underlying `<option value="...">` carries a stable identifier, switch the step to `match: value` and bind to that identifier instead:

```yaml
- action: select
  selector: 'select[data-cy="payrollId"]'
  match: value          # binds to <option value="..."> not the visible text
  value: "${params.planillaId}"
```

The portal's element inspector will show whether `<option>` tags carry meaningful `value=` attributes. If they're just `value="0"` / `value="1"` positional indices, label-matching is the only choice — but flag this in the descriptor so future-you knows the brittleness.

### Match employees by cédula uniqueness; surface name drift, don't reject

Submit-side adapters that match canonical employees against portal rows have **two** sources of truth: the cédula (national ID) and the display name. The cédula is the primary key — unique on a planilla by construction — and the display name is operator-typed text that drifts (typos, married-name updates, surname-order conventions, transliterated diacritics).

Use `EmployeeMatcher.matchWithDrift(canonicalId, canonicalName, ids, names)` rather than the strict `match`. It returns a `MatchResult{index, nameConfirmed}`:

- `nameConfirmed=true` — the cédula matched and the names confirm. Apply the salary, the happy path.
- `nameConfirmed=false` — the cédula matched **uniquely** but the names diverge (one canonical cédula appears exactly once on the portal side). Apply the salary anyway and emit a `NAME_DRIFT` signal to the result envelope's review block:

  ```java
  drifts.add(new SubmitResultBody.Signal(
          "NAME_DRIFT", null, null, null,
          row.identification(),
          c.name(),                   // canonical name (what we sent)
          row.name(),                 // portal name (what the portal shows)
          null, null));
  ```

  Status routes to `PARTIAL` so the HITL final-submit task fires before the planilla is billed; `rosterDiff` stays empty so the register/dereg lifecycle subprocesses do **not** run.

- `Optional.empty()` — either the cédula has no portal match (genuine missing employee → `MISSING_FROM_PORTAL`) or the cédula appears more than once on the portal side and the strict name confirm rejected (data corruption / split record → also `MISSING_FROM_PORTAL`, let HITL untangle it).

**Why not strict match?** The 2026-04-29 BPMN E2E exposed the trap: AutoPlanilla had `EVELYN GODINES` (operator typo, S), CCSS Sicere had `GODINEZ BOZA EVELYN NATALIA` (legal-registry spelling, Z). Cédula `207630807` matched uniquely. Strict matcher rejected → row went to `missingFromPortal` → salary never applied → reconciliation totals diverged for one row in 13. The cédula is the primary key on a planilla; strict name-confirm was overcautious for typo'd display names.

The strict `EmployeeMatcher.match` is preserved for callers where cédula collisions are plausible (cross-portal correlation, identifier reuse). For per-planilla submit flows where cédulas are unique by construction, default to `matchWithDrift`.

### SPA filter inputs may swallow `fill()` — use real keystrokes

Playwright's `Locator.fill()` sets `element.value=` directly and dispatches a single `input` event. Many SPA toolbars (search filters, type-ahead lookups) only respond to `keydown`/`keyup`/`input` events from the keyboard pipeline — `fill()` looks like nothing happened, the field stays empty, and the filter never fires. Verified on INS RT-Virtual 2026-05-04: `fill("117040400")` on the planilla search box left the field empty in the live browser; the magnifier-icon submit button next to it received no value to filter by.

Pattern that works:

```java
input.click();                                  // focus
input.press("Control+A");                       // select existing
input.press("Delete");                          // clear via real keystroke
input.pressSequentially(value,                  // per-char keydown/keyup/input
        new Locator.PressSequentiallyOptions().setDelay(40));
input.press("Tab");                             // or Enter, depending on the widget
```

If the search bar has its own submit button (magnifier icon) and you can't reliably anchor on it, **consider sidestepping search entirely.** INS RT-Virtual exposes a "Mostrando" rows-per-page combobox with options up to 200; bumping it once at startup put every row on a single rendered page and let the adapter anchor on `div.accordion-item:has-text(<cédula>)` directly — no search, no pagination, no reshuffle headaches. Reach for this trick when (a) the planilla fits in the max page size and (b) the search submit is finicky.

### Submit-result envelope: every required field must be a real value

The result-envelope schema is enforced at validate-on-publish. A POJO that builds a structurally correct shape but leaves a required string `null` will **DLQ silently** — the listener nacks the message, Praxis never sees a result, and the workflow stays in `Wait`. Same failure mode as OI-001, just from a different cause.

The trap that hit `ins-rt-virtual` on 2026-05-05: `SubmitResultBody.PortalConfirmation.rawText` is `String` (non-null per schema) and the adapter constructed it with `null` because the Resumen dialog's text wasn't preserved after parsing. Fix was to retain the dialog's `innerText` in an instance field and pass it through. **Always run a real BPMN E2E before declaring a new adapter done** — fixture tests and unit tests exercise the happy path inputs but don't catch this kind of validate-on-publish gap, since they don't go through the listener's publish path.

When in doubt, default required strings to the most useful information you have at that point: the verbatim portal-reported text, a formatted summary, or the empty string `""`. Never `null`.

### Wrap `beforeSteps` so failures still log out

`PortalRunService` invokes `afterCapture` only on the **success** path. When `beforeSteps` throws, the session is left logged in — and for shared-creds portals (INS, AutoPlanilla) every leaked session triggers a "concurrent session detected" modal on the next run, which the adapter then has to dismiss. The cycle gets noisier with each failure.

Standard wrapper:

```java
@Override
public void beforeSteps(...) {
    try {
        beforeStepsImpl(...);
    } catch (RuntimeException e) {
        performLogoutBestEffort(page, manifest, "logout-on-error");
        throw e;
    }
}
```

Where `performLogoutBestEffort` is a private method that tries the visible logout affordances (sidebar link first, then top-right user-menu) inside a try/catch — best-effort, never throws. Manifest gets a `logout-on-error` step on the failure path so the difference vs the success-path `logout` is visible.

### Continue on per-row save failure, don't abort

Submit-side adapters that loop over canonicals should treat each row's save as best-effort. A single row's failure (search returned 0/many, fill timeout, accordion not visible, race with portal-side reorder) **must not** abort the loop — the rest of the canonicals still need to land. Wrap each `saveRow` call:

```java
try {
    saveRow(page, row, c.salary(), bindings, manifest);
    matchedRows.add(...);
} catch (RuntimeException saveEx) {
    log.warn("save-failed canonicalId={} reason={}", c.id(), saveEx.toString());
    manifest.step("save-failed",
            "canonicalId=" + c.id() + " reason=" + saveEx.getMessage());
    fillFails.add(new SubmitResultBody.Signal(
            "FILL_FAILED", null, null, null,
            row.identification(), c.name(), c.salary(), null));
}
```

Status routes to `PARTIAL` when `fillFailures` is non-empty (alongside the existing `rosterDiff` and `nameDrift` triggers). HITL would rather see "12 of 14 saved, 2 need attention" with the specific cédulas flagged than a bare exception with everyone untouched. The `Review.summary` should append something like "N row(s) failed to save mid-loop — HITL must verify and resubmit".

### Verify session reuse before enabling it

Adding a `session: { ttlMinutes: <N> }` block tells the engine to save Playwright's `storageState()` after auth and reuse it on subsequent runs. That works for portals storing auth in **cookies** or **localStorage** — but `storageState()` does **not** capture `sessionStorage` or in-memory tokens. Many SPA-based portals (anything with a JWT held in a Redux store / `sessionStorage` / a service worker) fall into this second category.

Symptom of session reuse on an incompatible portal: the saved file is suspiciously small (a fresh AutoPlanilla session was 152 bytes, vs ~360 for cookie-based portals), and reused runs fail at the first post-login `waitForSelector` because the page lands logged-out.

Validation procedure for any new portal before enabling session reuse:

1. Run once with `session: { ttlMinutes: 30 }` and the descriptor authored.
2. Inspect `~/.financeagent/sessions/<portalId>.enc` size — under 200 bytes is suspicious.
3. Run a second time and check the manifest: `sessionReused=true` followed by a successful post-login step is the green flag.
4. If step 3 fails consistently, set `ttlMinutes: 0` and document the reason inline — every run will re-authenticate. The 2-3s login cost is a fair price for not chasing a phantom auth bug later.

---

## Troubleshooting during codegen

- **Selector looks fragile** (long `:nth-child` chains, hashed class names like `.MuiXxx-abc123`): click the Inspector's "Pick locator" button and let it suggest a role/label/text alternative. Paste both if unsure — I'll pick the more stable one.
- **Codegen can't see an element** (some custom components confuse the picker): open DevTools (F12) on the same browser window, grab the outerHTML of that element, paste it in chat with a note about which step it belongs to.
- **You realize mid-recording that you clicked the wrong thing**: just keep going — I'll prune extra steps from the translation. It's faster than re-recording.
- **The portal has a step that requires human input** (SMS code, email link): pause recording, tell me the shape of the step, resume after completing it. The `pause` engine action handles this category — the descriptor gets a step with `action: pause` at that point.

---

## Iterating on captured fixtures

Once the descriptor runs end-to-end against the real portal once, you usually don't want to hit the real portal for every subsequent selector tweak — the portal might rate-limit you, might not be in a submission window, might just be slow. The fixture-capture flow lets you iterate locally against a saved DOM snapshot:

```bash
# 1. Run the agent once with capture enabled
mvn -pl agent-worker exec:java \
  -Dportal.id=<portalId> \
  -Dparams.<key>=<value> ... \
  -Dfixture.capture=true

# 2. Promote the captured fixture into the test resources
cp artifacts/<runId>/fixtures/<portalId>-post-steps.html \
   agent-worker/src/test/resources/fixtures/<portalId>/post-steps.html

# 3. Write or update a JUnit test that uses DescriptorFixture to run
#    the scrape against the fixture and assert the expected values
```

See `DescriptorFixture` in the test sources for the helper API and `DescriptorFixtureTest` for an example usage. The test runs in CI without any portal available, so selector regressions land as red builds instead of silent production failures.

When the portal redesigns its UI and selectors stop matching, capture a fresh fixture and either update the existing test or add a second one — a promoted fixture from a real run is always the cheapest debugging asset.

---

## Next step: production rollout

When the descriptor runs cleanly locally and has fixture-test coverage, the production rollout path is in [PortalDeployment.md](PortalDeployment.md) — worker image build, Vault credential provisioning, BPMN wiring, staging rehearsal, and the per-firm enablement procedure. Dev and prod are deliberately separate docs so that each stays focused on one concern.

---

## Worked example: AutoPlanilla

Produced during M3a scaffolding. Files to look at:

- [agent-worker/src/main/resources/portals/autoplanilla.yaml](../agent-worker/src/main/resources/portals/autoplanilla.yaml) — the descriptor, still `TODO`-marked where codegen hasn't filled in selectors yet.
- [agent-worker/src/main/java/com/financialagent/worker/AutoplanillaAdapter.java](../agent-worker/src/main/java/com/financialagent/worker/AutoplanillaAdapter.java) — the adapter: how scraped strings become a typed `PayrollSummary` on the manifest.
- [agent-worker/src/main/java/com/financialagent/worker/portal/AutoplanillaMapper.java](../agent-worker/src/main/java/com/financialagent/worker/portal/AutoplanillaMapper.java) — the parser for Costa Rica currency and pagination text, with [unit tests](../agent-worker/src/test/java/com/financialagent/worker/portal/AutoplanillaMapperTest.java) covering the formats.
- [common-lib/src/main/java/com/financialagent/common/domain/PayrollSummary.java](../common-lib/src/main/java/com/financialagent/common/domain/PayrollSummary.java) — the typed record the adapter emits.

Four files per portal: descriptor (YAML), adapter (Java, one class), mapper if parsing is non-trivial (Java, one class + a test class), domain record (Java, `common-lib`). The generic pieces — engine, credentials provider, session store, Read-Back verifier, HAR scrubber — don't change per portal.
