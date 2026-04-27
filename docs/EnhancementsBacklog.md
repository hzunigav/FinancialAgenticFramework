# Enhancements Backlog

Improvements to pick up **after** the system is in production (post-M6 in [ImplementationPlan.md](ImplementationPlan.md)).
This is a growing list, not a roadmap — entries are captured as they come up so they don't get lost in chat history. Actual sequencing happens when we revisit the list post-deployment.

Each entry is sized and motivated in under a paragraph. If an entry is trivially in-scope for an upcoming milestone, it belongs in the ImplementationPlan instead of here.

---

## Active entries

### `--discover` mode for the agent-worker

**What:** A flag on the agent that — instead of running the scrape + capture/verify path — navigates through the descriptor's steps and dumps `page.content()` plus Playwright's accessibility snapshot (`page.accessibility.snapshot()`) to the run's artifact directory after each step. Also emits a YAML skeleton with Playwright-suggested locators for any new elements it sees.

**Why:** First-time selector capture is handled fine by `npx playwright codegen` in a developer's browser. But ongoing *maintenance* — when a portal redesigns its UI and our selectors break — needs something repeatable from inside our own tool. `--discover` lets an operator re-capture selectors with a single `mvn` command using the credentials and session the agent already manages, in the same environment where the real runs execute. No separate Node.js toolchain, no context switching.

**Size:** ~half a day. Reuses existing Playwright plumbing; most of the work is formatting the accessibility tree into a useful YAML template.

**Trigger to pick up:** first production portal redesign that breaks a selector, or whenever we onboard a third real portal — whichever comes first.

---

### OS-keychain credential storage

**What:** Alternative `CredentialsProvider` backed by Windows Credential Manager / macOS Keychain / Linux libsecret, so secrets never sit on disk at all — not even as an encrypted blob.

**Why:** `LocalFileCredentialsProvider` defends against backup/cloud-sync leaks, but an encrypted file plus a key file on the same disk is effectively one secret if malware runs as the user. OS keychains tie access to the platform login session and remove the "file-perms + ACL check" ceremony we currently carry. AWS Secrets Manager covers prod; this covers dev parity.

**Size:** half-day for Windows-only; ~1 day per additional platform. A cross-platform abstraction library would bundle them but adds a dependency.

**Trigger to pick up:** team grows past one or two developers, or a security review flags the on-disk credential file.

---

### Session-validity probe step

**What:** A first-step-in-`steps` probe that hits a cheap authenticated URL and checks the landed URL / response. If the loaded session is dead server-side, the engine purges it and replays `authSteps` automatically.

**Why:** Client-side TTL in the descriptor can disagree with server-side session lifetime (admin rotation, policy change, idle kick). Today a stale-on-server session looks like a broken selector downstream — the failure is misleading and requires manual session purge to recover. Probe turns it into an explicit, self-healing transition.

**Size:** half-day. Engine change + one descriptor field + a manifest status value (`SESSION_REFRESHED`).

**Trigger to pick up:** first production run that fails because of server-side session death not caught by the TTL.

---

### Manifest index HTML per run

**What:** A tiny `index.html` written into each run's artifact directory that summarizes the run (portal, status, verification result, duration) and links to the trace viewer, the HAR, the screenshot, and the raw `manifest.json`. One HTML per run, generated from a template.

**Why:** An auditor or new engineer opening an artifact directory sees a pile of files with no guidance on what to open first or what matters. An index makes one run navigable in under 10 seconds and is the kind of "make the audit story usable" polish that matters more as runs accumulate.

**Size:** half-day. Template rendering + a Java method, zero new dependencies.

**Trigger to pick up:** first time a non-engineer (auditor, stakeholder, compliance reviewer) has to examine a run.

---

### Lifecycle event envelopes (hire / termination)

**What:** New schemas under `contract-api/` for the operations beyond the v1 monthly cycle: `employee-register.v1.json` (Aviso de Ingreso for CCSS, parallel registrations for INS RT-Virtual and Hacienda OVI), `employee-deregister.v1.json` (Aviso de Egreso). Plus the agent-side adapters that drive each portal's hire/termination form. The v1 envelope already extends cleanly via `task.operation` and per-employee `attributes` map.

**Why:** Surfaced during the first contract design conversation (2026-04). The monthly cycle's `roster_diff` already names the gaps; the BPMN flow in [PayrollOrchestrationFlow.md](PayrollOrchestrationFlow.md) routes them to placeholder lifecycle subprocesses. Defer until at least one real target portal hire form is mapped — without DOM access we'd be designing on speculation, and field requirements per portal (CCSS occupation code, INS risk class, Hacienda residence status) need finance/HR confirmation first.

**Size:** 2-3 days per portal once finance has confirmed the field set: descriptor + adapter + per-portal hire/termination tests.

**Trigger to pick up:** first time the monthly cycle returns `PARTIAL` against a real portal (not the mock) and a real new hire / termination needs to flow through.

---

### ServiceLoader-based `PortalAdapter` registration

**What:** Replace the hardcoded `Agent.ADAPTERS = Map.of(...)` with `ServiceLoader<PortalAdapter>` discovery. Adding a portal = new adapter class + META-INF service entry; no edits to central code.

**Why:** Today two entries in a map, fine. At five portals the map becomes a merge-conflict hotspot between unrelated portals, and the "new portal = new YAML + new adapter" onboarding story silently carries a third touchpoint (the map) that will trip someone up.

**Size:** half-day. Straight `ServiceLoader` refactor; no framework added.

**Trigger to pick up:** onboarding the third real portal, or two portals being developed in parallel branches.

---

### Staging environment plan

**What:** Prefix-based split across all stateful systems — `financeagent/` for prod vs `financeagent-staging/` for staging on Vault mounts, matching RabbitMQ vhosts, worker deployments via env-driven config.

**Why:** Today the plan has local-dev and production; nothing in between. Pre-prod testing against real portals = real firm data and real regulatory exposure. A prefix-based staging costs ~1-2 days of setup and buys rehearsal surface that materially de-risks financial submissions.

**Size:** 1-2 days.

**Trigger to pick up:** deferred per 2026-04-23 decision. Revisit before the first customer-impacting production rehearsal, or if a pre-prod bug slips into production.

---

### Secret-rotation handling on the worker

**What:** On portal 401 / 403 during a run, the worker purges its cached session, fetches fresh credentials from Vault, retries the current step once. Same pattern for Vault AppRole token refresh on expiry.

**Why:** The worker fetches credentials once at run start. When portals rotate passwords or AppRole tokens expire mid-run, the failure mode is silent outage until the next run, which may be a day later. Rotation-aware retry turns this into transparent recovery.

**Size:** half a day once the `VaultCredentialsProvider` exists.

**Trigger to pick up:** deferred per 2026-04-23 decision. Revisit before first credential rotation event, or when first AppRole token approaches expiry.

---

### `PraxisIntegrationHandoff.md` changelog breadcrumb

**What:** Add a "Changelog" section at the top of [PraxisIntegrationHandoff.md](PraxisIntegrationHandoff.md) listing the last N material changes with commit SHAs, or per-subsection tags referencing the commit that last touched them.

**Why:** As the handoff doc grows and Praxis has follow-up conversations with us, they will ask version-dependent questions ("does this match the commit you sent over last week?"). A small changelog lets them triage what changed without re-reading the whole doc.

**Size:** 15 minutes initially + ongoing discipline at each doc-affecting commit.

**Trigger to pick up:** before the second handover conversation with Praxis (not strictly required for the first since the whole thing is new to them).

---

## Shipped

### Descriptor DSL: `when`, `expect`, `while`

**Shipped:** conditional branches, assertions, and pagination loops are now declarative. `when` / `expect` / `while` added to [PortalEngine](../agent-worker/src/main/java/com/neoproc/financialagent/worker/portal/PortalEngine.java) with 9 new tests in [PortalEngineControlFlowTest](../agent-worker/src/test/java/com/neoproc/financialagent/worker/portal/PortalEngineControlFlowTest.java). Unblocks Phase 2 portal authoring — CCSS/INS/Hacienda can now handle ToS prompts, post-submit assertions, and paginated result sets without adapter-side Java forks.

**Not shipped (deferred):** per-step `timeoutMs` / `retries` overrides. Lower priority; revisit when a specific Phase 2 portal needs a non-default timeout.

---

### `AbstractCaptureAdapter` / `AbstractSubmitAdapter` base classes

**Shipped:** envelope plumbing (metadata, encryption, audit hashing, file write) extracted into two abstract templates; common helpers into `BaseAdapter`. Concrete adapters now implement one hook (`buildCaptureOutcome` or `buildSubmitOutcome`). [AutoplanillaAdapter](../agent-worker/src/main/java/com/neoproc/financialagent/worker/AutoplanillaAdapter.java) shrank from 132 to 73 lines (45% reduction); [MockPayrollAdapter](../agent-worker/src/main/java/com/neoproc/financialagent/worker/MockPayrollAdapter.java) from 381 to 334 (envelope boilerplate gone — the remaining lines are genuine adapter logic). New Phase 2 adapters land at ~50-100 lines each rather than copying envelope plumbing per portal.

---

### Descriptor replay harness (fixture-capture + offline scrape test)

**Shipped:** `-Dfixture.capture=true` flag dumps the fully-rendered DOM to `artifacts/<runId>/fixtures/<portalId>-post-steps.html` at scrape time; a `DescriptorFixture` test utility plus `DescriptorFixtureTest` let CI exercise a descriptor's scrape section against a captured fixture with no live portal. Per-portal regression tests for Phase 2 plug into this pattern. Full step-level replay (record fill/click/submit, replay them) not implemented — the 80% case is verifying selectors still match, which the scrape-only harness covers.

---

### Split `PortalOnboarding.md` into dev and production workflows

**Shipped:** [PortalOnboarding.md](PortalOnboarding.md) is now dev-focused, with a new "Iterating on captured fixtures" section and a "Next step: production rollout" pointer. [PortalDeployment.md](PortalDeployment.md) covers the production path: worker image + K8s bake-in, Vault credential provisioning (per-firm vs shared), BPMN wiring, smoke test, per-firm enablement, rollback procedure, and post-deploy ownership. Each doc has a cross-link to the other so readers land in the right place.

---

## How to add an entry

Copy the template below, append under **Active entries**, and keep it to four short lines plus a one-line trigger.

```markdown
### <title>

**What:** <one sentence on the change>
**Why:** <one or two sentences on the motivation — prefer a concrete pain point over a generic improvement>
**Size:** <rough effort: hours / half-day / days / week>
**Trigger to pick up:** <a specific condition that would make this worth prioritizing, not a date>
```

When an entry is picked up, move it under a new **Shipped** section with the commit SHA and a one-line outcome — so the backlog doubles as a record of what improvements actually happened.
