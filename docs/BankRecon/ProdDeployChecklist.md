# BankRecon (Xero) — Prod deploy & test checklist

Owner-tagged action tracker. Detailed commands: see `ProdDeployRunbook.md`.
Code is complete on `feat/bankrecon-xero-upload`; everything below is ops/coordination.

## 0. Prereqs (you, before starting)
- [ ] Xero login account's 2FA method is **authenticator app (TOTP)** — NOT SMS or Xero Verify push.
- [ ] Have the account's **base32 TOTP seed** (from the authenticator enrolment / Xero 2FA setup).
- [ ] CloudShell access with the deploy role; confirm these prod resources exist (they do for the
      other portals): DLQ `prod-financeagent-dlq`, ECR repo `financeagent-worker`, artifacts bucket
      `prod-financeagent-artifacts`.
- [ ] Decide run-1 path: **pre-seed the session** (skips 2FA on first run) OR **cold-login**
      (exercises the autonomous TOTP path in prod — recommended since you're testing in prod).

## 1. Ship the code (you, ~10 min)
- [ ] Merge PR `feat/bankrecon-xero-upload` → `main`.
- [ ] In the `deploy-agent-worker` Actions run: confirm the **build job** pushed the image; copy the
      tag **`prod-<first8-of-sha>`**.
- [ ] Expect the **`xero` deploy step to FAIL** ("service not found") — normal on first deploy
      (service doesn't exist yet). Other portals deploy fine.

## 2. Provision (you, CloudShell)
- [ ] Create the credential secret:
      `aws secretsmanager create-secret --name prod/financeagent/shared/portals/xero
       --secret-string '{"username":"…","password":"…","totpSeed":"BASE32SEED"}'`
- [ ] `source <(./scripts/prod-infra-setup.sh --print-env)`
- [ ] `./scripts/prod-infra-setup.sh xero bankstatement prod-<sha>`
      (creates queues + log group + task def + the `financeagent-bankstatement-xero` service)
- [ ] Grant the **task role** the IAM the script echoes:
  - [ ] `secretsmanager:GetSecretValue` on `prod/financeagent/shared/portals/xero-*`
  - [ ] `secretsmanager:Get/Put/Create/DeleteSecret` on `prod/financeagent/sessions/xero-*`
  - [ ] `sqs:ReceiveMessage/DeleteMessage/GetQueueAttributes` on the task-queue ARN
  - [ ] `sqs:SendMessage` on the results-queue ARN
  - [ ] `kms:Encrypt/Decrypt/GenerateDataKey` on the session key — **only if** you use a CMK
  - [ ] `s3:PutObject` on the artifacts bucket (already granted if other portals run)
- [ ] (Optional, CMK) set `FINANCEAGENT_SESSION_KMS_KEY_ID` in the xero env block + re-run the script.
- [ ] (Optional) pre-seed the session via `SessionSeeder` if you chose that path in step 0.

## 3. Verify it's up (you)
- [ ] `describe-services financeagent-bankstatement-xero` → `runningCount: 1`.
- [ ] `aws logs tail /ecs/financeagent-bankstatement-xero --follow` → `BankStatementTaskListener`
      registered, no crash loop.
- [ ] If a scaling schedule applies, ensure desired-count ≥ 1 while testing.

## 4. Test — E2E with Praxis (you + Praxis)
- [ ] Praxis publishes a real request to `prod-financeagent-tasks-bankstatement-xero`
      (CSV header EXACTLY `*Date,*Amount,Payee,Description,Reference,Check Number`).
- [ ] Logs show: login → org switch → account match → import.
- [ ] Result lands on `prod-financeagent-bankstatement-results` with the **same businessKey**,
      `status=SUCCESS`; Praxis routes archive vs HITL.
- [ ] **Duplicate path:** re-publish the same statement → `status=MISMATCH`,
      `errorCategory=DUPLICATE_STATEMENT` → Praxis routes to **HITL, not DLQ**.
- [ ] **2FA path (if not pre-seeded):** first run hits the TOTP challenge → worker fills it + ticks
      "trust this device" + proceeds. **If it fails AUTH**, fetch
      `s3://prod-financeagent-artifacts/prod/runs/xero/<runId>/2fa-challenge.html` and send it to me
      → I harden the selectors → you redeploy (push to main).
- [ ] Spot-check artifacts in `s3://prod-financeagent-artifacts/prod/runs/xero/<runId>/`.

## 5. Inform Praxis (you)
- [ ] Send them `PraxisCoordinationNote.md` (contract fields, the 2 resolved gaps, queue + secret
      names). **Queues are unchanged** from what you already communicated.
- [ ] If they keep dashboards/runbooks/on-call docs, the Xero service name is now
      **`financeagent-bankstatement-xero`**.
- [ ] Cost decision to raise with them: Xero worker is **always-on (+~$31/mo Graviton)** by default;
      a daily 1-hour scaled window (sibling Lambda matching `financeagent-bankstatement-`) is
      **+~$1.50/mo**. Independent of the naming; decide based on the daily cadence.

## Optional help available from me
- [ ] A NeoProc-only **smoke-test `request.v1.json`** (inline base64 `DemoCoBank.csv`) so you can
      `aws sqs send-message` to the task queue and test the worker *without* Praxis first.
- [ ] Harden the `XERO_2FA_*` selectors from the first real `2fa-challenge.html` dump.
