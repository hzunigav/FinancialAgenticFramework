# Agent Smoke Test Requirements

Specification for the agent-worker team. These tests verify that the browser
automation layer can authenticate to each production portal before a live
payroll cycle is attempted. They are independent of Praxis BPMN orchestration
and live in the agent-worker repository.

## Overview

Three test tiers:

| Tier | What | When |
|------|------|------|
| **Login probe** | Login + navigate to target view + screenshot | Before each production cycle run; on deploy |
| **Submission smoke** | Full mock cycle through mock-payroll adapter | After agent-worker deploy |
| **Production cycle** | Real payroll submission — May cycle | Late May (payroll window opens) |

This document covers **Tier 1: Login probes**.

---

## 1. AutoPlanilla — Login + Navigation Probe

### Purpose
Verify that the shared NeoProc credentials for AutoPlanilla are valid and that
the agent can reach the Generar Planilla (report generation) view, which is the
entry point for payroll capture.

### Inputs

| Field | Value |
|-------|-------|
| Portal | `autoplanilla` |
| Secret path (AWS SM) | `financeagent/shared/portals/autoplanilla` |
| Secret fields | `username`, `password` |

### Steps the agent must execute

1. Navigate to the AutoPlanilla login URL.
2. Enter `username` and `password` from the resolved secret.
3. Verify successful login — detect a known post-login DOM selector
   (e.g. the main navigation bar or dashboard heading). Fail with
   `CREDENTIALS_INVALID` if the login error banner appears.
4. Navigate to the **Generar Planilla** (payroll report generation) view.
5. Verify the view loaded — detect the planilla selector dropdown or
   equivalent stable DOM element.
6. Take a full-page screenshot. Store to S3 (or local file in dev) and record
   the SHA-256 in the result.
7. Log out cleanly.

### Output (success)

```json
{
  "portal": "autoplanilla",
  "operation": "LOGIN_PROBE",
  "status": "SUCCESS",
  "navigation": "report-generation-view",
  "screenshotPath": "s3://praxis-artifacts/probes/autoplanilla/YYYY-MM-DD_HH-mm-ss.png",
  "screenshotSha256": "<hex>",
  "durationMs": 4200
}
```

### Output (failure)

```json
{
  "portal": "autoplanilla",
  "operation": "LOGIN_PROBE",
  "status": "FAILED",
  "errorCategory": "CREDENTIALS_INVALID | NAVIGATION_ERROR | TIMEOUT",
  "errorDetail": "Login error banner detected after submit",
  "screenshotPath": "s3://praxis-artifacts/probes/autoplanilla/YYYY-MM-DD_HH-mm-ss_failure.png"
}
```

### Assertion checklist (for the agent-worker test)

- [ ] Login page loaded (title contains "AutoPlanilla" or equivalent)
- [ ] No error banner after credential submit
- [ ] Post-login dashboard element visible within 10 s
- [ ] Generar Planilla navigation item clickable
- [ ] Report generation view loaded within 15 s
- [ ] Screenshot file is non-empty, SHA-256 matches stored value

---

## 2. INS RT Virtual — Login Probe

### Purpose
Verify that the shared NeoProc credentials for INS RT Virtual are valid and that
the agent can reach the post-login portal state (salary submission section).
INS uses a shared login; the per-firm client selector (cédula jurídica) is
resolved from the `firm_portal_identifier` row for `ins-rt-virtual`.

### Inputs

| Field | Value |
|-------|-------|
| Portal | `ins-rt-virtual` |
| Secret path (AWS SM) | `financeagent/shared/portals/ins-rt-virtual` |
| Secret fields | `username`, `password`, `mfaMethod` |
| Client identifier | NeoProc cédula jurídica (from `firm_portal_identifier.client_identifier` where `portal_id='ins-rt-virtual'`) |

### Steps the agent must execute

1. Navigate to the INS RT Virtual login URL.
2. Enter `username` and `password`.
3. Handle MFA if `mfaMethod != "none"` — the probe should support `TOTP` and
   `EMAIL_OTP`; skip the step for `none`.
4. Detect successful login (dashboard heading or main menu).
5. Navigate to the salary submission / nómina section.
6. Verify the firm selector shows NeoProc's cédula jurídica in the context.
7. Take a screenshot of the post-login dashboard.
8. Log out.

### Output

Same shape as § 1. `navigation` value: `"salary-submission-dashboard"`.

### Assertion checklist

- [ ] Login page loaded
- [ ] No error after credential submit
- [ ] Post-login element visible within 15 s
- [ ] Salary submission section reachable
- [ ] Screenshot captured

---

## 3. CCSS SICERE — Login Probe (per-client scope)

### Purpose
CCSS uses **per-client credentials** (one login per employer cédula jurídica).
The probe must resolve the per-client corporate ID from the inbound test
parameters, load the matching credential from Secrets Manager, and verify login
works for that specific client.

This probe should be parameterized by `clientIdentifier` so it can be run for
each NeoProc client that has a `client_portal_identifier` row with
`portal_id = 'ccss-sicere'`.

### Inputs

| Field | Value |
|-------|-------|
| Portal | `ccss-sicere` |
| Secret path (AWS SM) | `financeagent/firms/{firmId}/portals/ccss-sicere/{clientIdentifier}` |
| Secret fields | `username`, `password` |
| Client identifier | Per-client cédula jurídica (parameter — see note below) |

> **Dev/staging note:** the localstack-init.sh seeds a single test client with
> `clientIdentifier = 3-101-680139`. Use that value for local smoke runs.
> For production, run the probe once per distinct cédula in
> `client_portal_identifier` where `portal_id = 'ccss-sicere'`.

### Steps the agent must execute

1. Navigate to the CCSS SICERE login URL.
2. Enter `username` and `password` (per-client credentials).
3. Detect successful login — planilla employer selection page or main menu.
4. Verify the employer cédula displayed matches the expected `clientIdentifier`.
5. Take a screenshot.
6. Log out.

### Output

Same shape as § 1. `navigation` value: `"employer-planilla-selector"`.

### Assertion checklist

- [ ] Login page loaded
- [ ] No error after credential submit
- [ ] Employer selection page visible within 15 s
- [ ] Displayed cédula matches `clientIdentifier` parameter
- [ ] Screenshot captured

---

## 4. Trigger mechanism

Login probes can be triggered in two ways:

### 4a. Standalone agent-worker test (recommended for CI)

A JUnit/pytest test class (no SQS involved) that:
- Bootstraps a Playwright browser session
- Reads credentials from `LocalFileCredentialsProvider` or a test AWS profile
- Runs the portal navigation steps above
- Asserts DOM selectors and screenshot non-emptiness
- Tagged `@Tag("portal-smoke")` — excluded from `mvn test`, included in a
  dedicated `mvn verify -Pportal-smoke` profile

### 4b. SQS probe envelope (for integration with Praxis audit trail)

For production readiness checks that should appear in the Praxis audit log,
the probe is triggered as a standard `payroll-capture-request.v1` envelope
whose `envelope.businessKey` starts with `probe::`. No new schema, no new
operation enum, no new queue — the existing capture-request schema and the
existing capture queue carry it. The listener inspects `businessKey` and
dispatches to the login-only `PortalRunService.runProbe` path instead of the
full capture path.

**Envelope shape (standard capture-request, businessKey marks it as a probe):**

```json
{
  "schema": "payroll-capture-request.v1",
  "envelope": {
    "envelopeId": "<uuid>",
    "businessKey": "probe::<portalId>::<YYYY-MM-DD>",
    "firmId": 1,
    "locale": "es",
    "createdAt": "<iso8601>",
    "issuer": "praxis-smoke",
    "issuerRunId": "<uuid>"
  },
  "task": {
    "type": "PAYROLL_CAPTURE",
    "operation": "CAPTURE",
    "sourcePortal": "<autoplanilla|ccss-sicere|ins-rt-virtual>",
    "planilla": {
      "id": "<clientIdentifier — required for per-client portals e.g. ccss-sicere>",
      "name": "probe"
    }
  },
  "encryption": null,
  "request": null,
  "audit": { "manifestPath": null, "harSha256": null, "screenshotSha256": null, "payloadSha256": null }
}
```

**Implementation note:**
- Dispatch signal: `envelope.businessKey.startsWith("probe::")` —
  see `PayrollCaptureListener.executeProbe`.
- For per-client portals (ccss-sicere) the per-client cédula travels on
  `task.planilla.id`; the listener copies it into the
  `params.clientIdentifier` binding so the credentials provider resolves
  the matching per-client secret. Shared-scope portals leave `task.planilla`
  null (or set `id=null`).
- The probe returns a minimal `PayrollCaptureResult` with `status = SUCCESS`
  (or `FAILED` on login error), `result.employees = []`, zero totals, and
  `audit.screenshotSha256` populated from the full-page probe screenshot.
- `businessKey` is echoed verbatim on the result (per the businessKey
  passthrough rule) so the Praxis BPM correlation works the same as on a
  normal capture cycle.

---

## 5. Screenshot storage

| Environment | Storage | Path pattern |
|-------------|---------|--------------|
| Local dev | Local file | `~/.financeagent/probes/<portalId>/<timestamp>.png` |
| Staging / prod | AWS S3 | `s3://<PRAXIS_ARTIFACTS_BUCKET>/probes/<portalId>/<firmId>/<timestamp>.png` |

SHA-256 is computed by the agent after writing the file and returned in the
result's `audit.screenshotSha256` field. Praxis will surface this in the audit
event for the probe cycle.

---

## 6. Secrets Manager secret paths (production)

The agent team must provision the following secrets in AWS Secrets Manager
before the production probes can run. Paths follow the existing contract-api
convention (see `docs/PraxisIntegrationHandoff.md §5`):

| Portal | Scope | Secret path |
|--------|-------|-------------|
| AutoPlanilla | Shared | `prod/financeagent/shared/portals/autoplanilla` |
| INS RT Virtual | Shared | `prod/financeagent/shared/portals/ins-rt-virtual` |
| CCSS SICERE | Per-client | `prod/financeagent/firms/1/portals/ccss-sicere/<cédula>` (one per client) |
| Hacienda OVI | Shared | `prod/financeagent/shared/portals/hacienda-ovi` |

For **mock-payroll** the LocalStack secret at
`dev/financeagent/shared/portals/mock-payroll` is already seeded by
`localstack-init.sh` — no additional provisioning needed for local dev.

---

## 7. Data Praxis must supply to the agent team

Before the production probes can be provisioned, Humberto needs to provide:

| Item | Where to find it | For which portal |
|------|-----------------|-----------------|
| NeoProc firm cédula jurídica for INS | INS account registration | `ins-rt-virtual` |
| NeoProc firm cédula jurídica for Hacienda OVI | D-103 registration | `hacienda-ovi` |
| Per-client cédula jurídica list (CCSS) | Entered in Praxis → Firm → Client Portal Identifiers | `ccss-sicere` (one row per client) |
| AutoPlanilla shared credentials | Existing AutoPlanilla account | `autoplanilla` |
| INS RT Virtual shared credentials | INS portal admin account | `ins-rt-virtual` |
| CCSS SICERE per-client credentials | One username/password per employer cédula | `ccss-sicere` |
| Hacienda OVI shared credentials | Hacienda account | `hacienda-ovi` |

---

## 8. Definition of done (agent team)

- [ ] Standalone Playwright test class per portal (§4a)
- [ ] Tests run headless in `mvn verify -Pportal-smoke`
- [ ] `businessKey: probe::*` dispatch (§4b) — listener routes to
      `PortalRunService.runProbe`, returns minimal `PayrollCaptureResult`
      with status + screenshot SHA-256, `result.employees = []`,
      `businessKey` echoed verbatim
- [ ] Per-client portals (ccss-sicere): `task.planilla.id` copied into
      `params.clientIdentifier` binding so the correct per-client secret is
      resolved
- [ ] Screenshot written to local file in dev, S3 in prod
- [ ] Probe assertions documented in test class Javadoc
- [ ] Secret paths provisioned in LocalStack for dev (see `localstack-init.sh`
      pattern) — at minimum for `autoplanilla` and `ins-rt-virtual`
- [ ] README section added for running portal-smoke profile
