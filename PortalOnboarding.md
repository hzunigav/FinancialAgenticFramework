# Portal Onboarding

How to add a new portal to the framework — from *"here's a URL I want to scrape"* to *"the agent is pulling data from it."*

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

## Troubleshooting during codegen

- **Selector looks fragile** (long `:nth-child` chains, hashed class names like `.MuiXxx-abc123`): click the Inspector's "Pick locator" button and let it suggest a role/label/text alternative. Paste both if unsure — I'll pick the more stable one.
- **Codegen can't see an element** (some custom components confuse the picker): open DevTools (F12) on the same browser window, grab the outerHTML of that element, paste it in chat with a note about which step it belongs to.
- **You realize mid-recording that you clicked the wrong thing**: just keep going — I'll prune extra steps from the translation. It's faster than re-recording.
- **The portal has a step that requires human input** (SMS code, email link): pause recording, tell me the shape of the step, resume after completing it. The `pause` engine action handles this category — the descriptor gets a step with `action: pause` at that point.

---

## Worked example: AutoPlanilla

Produced during M3a scaffolding. Files to look at:

- [agent-worker/src/main/resources/portals/autoplanilla.yaml](agent-worker/src/main/resources/portals/autoplanilla.yaml) — the descriptor, still `TODO`-marked where codegen hasn't filled in selectors yet.
- [agent-worker/src/main/java/com/financialagent/worker/AutoplanillaAdapter.java](agent-worker/src/main/java/com/financialagent/worker/AutoplanillaAdapter.java) — the adapter: how scraped strings become a typed `PayrollSummary` on the manifest.
- [agent-worker/src/main/java/com/financialagent/worker/portal/AutoplanillaMapper.java](agent-worker/src/main/java/com/financialagent/worker/portal/AutoplanillaMapper.java) — the parser for Costa Rica currency and pagination text, with [unit tests](agent-worker/src/test/java/com/financialagent/worker/portal/AutoplanillaMapperTest.java) covering the formats.
- [common-lib/src/main/java/com/financialagent/common/domain/PayrollSummary.java](common-lib/src/main/java/com/financialagent/common/domain/PayrollSummary.java) — the typed record the adapter emits.

Four files per portal: descriptor (YAML), adapter (Java, one class), mapper if parsing is non-trivial (Java, one class + a test class), domain record (Java, `common-lib`). The generic pieces — engine, credentials provider, session store, Read-Back verifier, HAR scrubber — don't change per portal.
