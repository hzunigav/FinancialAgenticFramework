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

### ServiceLoader-based `PortalAdapter` registration

**What:** Replace the hardcoded `Agent.ADAPTERS = Map.of(...)` with `ServiceLoader<PortalAdapter>` discovery. Adding a portal = new adapter class + META-INF service entry; no edits to central code.

**Why:** Today two entries in a map, fine. At five portals the map becomes a merge-conflict hotspot between unrelated portals, and the "new portal = new YAML + new adapter" onboarding story silently carries a third touchpoint (the map) that will trip someone up.

**Size:** half-day. Straight `ServiceLoader` refactor; no framework added.

**Trigger to pick up:** onboarding the third real portal, or two portals being developed in parallel branches.

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
