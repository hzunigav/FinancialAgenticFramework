# Portal Deployment

How a portal descriptor goes from *"working locally on my machine"* to *"running in production under Praxis orchestration, against a specific firm."*

This is the companion to [PortalOnboarding.md](PortalOnboarding.md), which covers descriptor authoring (Playwright codegen → YAML → local iteration). Once `mvn exec:java -Dportal.id=<id> ...` runs cleanly and the envelope looks right, come here.

**Preconditions for anything in this doc to apply:**
1. Phase 1 of the [Praxis integration](PraxisIntegrationHandoff.md) has landed: AWS KMS + Secrets Manager policies + IAM roles, RabbitMQ topology, worker runtime deployed, BPMN plumbing live.
2. The descriptor is merged on `main`, has unit/fixture test coverage, and has at least one clean end-to-end run recorded in `artifacts/`.
3. A payroll administrator in the target firm has already been onboarded in Praxis (so their `firmId` exists and the firm record is available to write the portal-specific metadata into).

If those aren't true yet, this doc is premature — stay in [PortalOnboarding.md](PortalOnboarding.md) until dev mode is solid.

---

## 1. Bake the descriptor into the worker image

Worker container images are built per-portal ([PraxisIntegrationHandoff.md §F.2](PraxisIntegrationHandoff.md#f2-orchestration)). A new descriptor needs:

- [ ] Descriptor YAML under `agent-worker/src/main/resources/portals/<portalId>.yaml` (dev prereq, repeated here as a sanity check).
- [ ] Adapter class extending `AbstractCaptureAdapter` or `AbstractSubmitAdapter` — registered in `Agent.ADAPTERS`.
- [ ] New ECS Fargate task definition + service: `financeagent-worker-<portalId>` with `PORTAL_ID=<portalId>`, the task IAM role from [InfraSetup.md §B.3](InfraSetup.md), RabbitMQ connection from Secrets Manager, `FINANCEAGENT_CIPHER=kms`, `FINANCEAGENT_CREDENTIALS=aws`.
- [ ] RabbitMQ queue declared: `financeagent.tasks.submit.<portalId>` (or `.capture` for source-of-truth adapters) bound to the correct exchange with the portal's rate-limit-aware prefetch ([§C.5](PraxisIntegrationHandoff.md#c5-per-portal-rate-limiting)).

No descriptor changes go to production without a matching worker image and K8s deployment. Merging a descriptor without these is a configuration bug waiting to happen.

---

## 2. Provision credentials in Secrets Manager

The path depends on the descriptor's `credentialScope` ([PraxisIntegrationHandoff.md §3 B.1](PraxisIntegrationHandoff.md#b1-secrets-manager-layout)):

**Per-firm portal (e.g., CCSS Sicere):**
- [ ] Payroll admin enters the firm's credentials in the Praxis firm-admin UI. UI writes to `financeagent/firms/<firmId>/portals/<portalId>`.
- [ ] Admin never sees, types, or stores credentials in any file on any machine — not locally, not in a ticket, not in Slack. If the workflow tempts them to, the UI has a gap; file a UX bug rather than working around it.

**Shared-creds portal (e.g., INS, Hacienda, AutoPlanilla):**
- [ ] A tenant-admin (not a payroll admin) enters NeoProc's single login to `financeagent/shared/portals/<portalId>` — one time per portal, not per firm.
- [ ] For each firm that will use this portal, the payroll admin enters the firm's **client identifier** on that portal (cédula jurídica / internal client code — not a secret) in the firm record. This lands on the envelope's `task.clientIdentifier` at dispatch time.

**Verification** (for both scopes):
```bash
# Per-firm
aws secretsmanager get-secret-value --secret-id financeagent/firms/<firmId>/portals/<portalId>
# Shared
aws secretsmanager get-secret-value --secret-id financeagent/shared/portals/<portalId>
```

Should return the credential JSON in `SecretString` (password is present in cleartext over TLS — the AWS CLI does not mask it). If it returns `ResourceNotFoundException`, onboarding didn't complete — do not proceed to rollout.

---

## 3. Wire the BPMN Service Task

Praxis-side work, but worth listing so the descriptor team can catch a missing step in review:

- [ ] The BPMN process has a Service Task for the new portal that publishes to `financeagent.tasks.submit.<portalId>` with the envelope properties from [PraxisIntegrationHandoff.md §C.2](PraxisIntegrationHandoff.md#c2-message-envelope).
- [ ] Praxis dispatches the envelope built from the upstream capture result + any shared-creds `clientIdentifier` looked up from the firm record.
- [ ] A matching Receive Task correlates on `envelope.businessKey` and routes the result through the status gateway from [PayrollOrchestrationFlow.md §4](PayrollOrchestrationFlow.md#4-status-vocabulary--routing).

---

## 4. Smoke-test in staging

Before enabling the descriptor for any real firm:

- [ ] Publish a hand-crafted test envelope to `financeagent.tasks.submit.<portalId>` (or capture-side equivalent) from the staging RabbitMQ management UI.
- [ ] Observe the new worker picks it up (logs show `Consumed envelopeId=...`).
- [ ] Result envelope lands on `financeagent.results` with matching `correlation-id`.
- [ ] Artifacts uploaded to the configured S3 bucket ([§F.3](PraxisIntegrationHandoff.md#f3-artifact-retention)) — manifest, HAR, screenshot, encrypted envelope all present.
- [ ] For shared-creds portals: the portal-side action visibly targeted the right client (check the screenshot, check the portal itself if possible).

If any of these fail, roll back before enabling for production firms.

---

## 5. Enable for production firms

One firm at a time, in order of risk tolerance:

- [ ] Pick the pilot firm. Ideally one that the payroll admin team knows well and that is willing to supervise the first cycle.
- [ ] Enable the descriptor in the firm's Praxis configuration (flag, toggle, or the firm's enabled-portals list — depends on Praxis UI).
- [ ] Run one cycle under supervision. Expect: an in-band HITL review if any drift exists between source-of-truth and target portal. That's not a bug — that's the whole point of `roster_diff`.
- [ ] Monitor two more cycles before rolling out to additional firms. Watch for:
  - False-positive `MISMATCH`es (the agent disagrees with the portal when it shouldn't)
  - Timing issues (agent timing out on a page that's slower for this firm than for the pilot)
  - Memory / CPU pressure on the worker pod that wasn't present with the baseline portals

- [ ] After two clean cycles, roll out to the remaining firms in waves of two or three, monitoring after each wave.

---

## 6. Rollback procedure

If the descriptor starts misbehaving in production:

1. **Disable at the firm level first.** Flip the firm's enabled-portals toggle. BPMN will skip the Service Task for that firm; other firms keep running.
2. If the problem is specific to one firm (e.g., their portal account has a quirk), triage with that firm's admin. The other firms stay online.
3. If the problem is descriptor-wide (new selectors broken, portal redesigned), **pause the queue**: from the RabbitMQ UI, pause consumption on `financeagent.tasks.submit.<portalId>`. Envelopes accumulate but no runs fire. Praxis shows pending tasks but nothing goes wrong on the portal side.
4. Fix in dev against a fresh captured fixture (see `-Dfixture.capture=true` in [PortalOnboarding.md](PortalOnboarding.md#iterating-on-captured-fixtures)).
5. Rebuild + redeploy the worker image. Resume the queue.

Never leave a broken descriptor running against a portal — submitting bad data to a government payroll system is worse than submitting nothing.

---

## 7. Ownership after rollout

Once a descriptor is in production:

- **Selector rot** (portal redesigns its UI): descriptor team. Watch for mysterious timeout failures concentrated after a portal maintenance window — usually a sign.
- **Rate-limit tuning** (portal throttles us harder than expected): descriptor team sets `rateLimit` in the YAML; ops adjusts RabbitMQ prefetch to match.
- **HITL queue triage** (reviewing PARTIAL / MISMATCH envelopes): payroll admins at the firm, via the Praxis review UI. If the queue grows faster than it drains, that's a workload issue — flag it to the firm's operations manager, not the descriptor team.
- **Credential rotation** (portal forces a password change): the firm's payroll admin re-enters credentials via the Praxis UI. The worker's next run picks them up from Secrets Manager automatically; no redeploy needed.

---

## Appendix: iterating on captured fixtures

During dev (covered in [PortalOnboarding.md](PortalOnboarding.md)) and during any post-deploy bug investigation, capture a fixture with:

```
mvn -pl agent-worker exec:java \
  -Dportal.id=<portalId> \
  -Dparams.<key>=<value> ... \
  -Dfixture.capture=true
```

The resulting `artifacts/<runId>/fixtures/<portalId>-post-steps.html` is the fully-rendered DOM at scrape time. Copy it into `agent-worker/src/test/resources/fixtures/<portalId>/` and write a JUnit test using [DescriptorFixture](agent-worker/src/test/java/com/neoproc/financialagent/worker/portal/DescriptorFixture.java) to assert the scrape values. That test then catches future selector regressions without needing the portal available.
