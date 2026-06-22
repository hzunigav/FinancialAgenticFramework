# BankRecon (Xero) — Prod deploy & test runbook

Ordered steps to take the `PORTAL_ID=xero` bank-statement worker to production and test it
there. Assumes the existing prod account (`409159414704`, `us-east-1`, ECS cluster
`financeagent`) with the shared DLQ `prod-financeagent-dlq` and ECR repo `financeagent-worker`
already in place (used by the other portals).

The prerequisite code is committed on `feat/bankrecon-xero-upload` (adapter, run path,
autonomous 2FA, `xero.yaml`, `xero` added to the deploy matrix, `bankstatement` provisioning).

---

## Step 1 — Merge to `main` → build the image
PR `feat/bankrecon-xero-upload` → `main`. On merge, `deploy-agent-worker.yml`:
- builds & pushes an ARM64 image **`prod-<first8-of-sha>`** to ECR, and
- runs the deploy matrix. The **`xero` deploy step will FAIL this first time** ("service not
  found — run prod-infra-setup.sh first"). That's expected; `fail-fast: false` means the other
  portals still deploy. **Copy the image tag** from the build job log (e.g. `prod-1a2b3c4d`).

(The contract-api publish workflow only fires on `contract-api/**`/`pom.xml` changes — contracts
are already published, so nothing to do there.)

## Step 2 — Create the shared credential secret (CloudShell)
The Xero account's 2FA method must be **authenticator-app TOTP** (not SMS / Xero-Verify push).
```bash
aws secretsmanager create-secret \
  --name prod/financeagent/shared/portals/xero \
  --secret-string '{"username":"<xero-login-email>","password":"<password>","totpSeed":"<BASE32-TOTP-SEED>"}'
```
(The session secret `prod/financeagent/sessions/xero` is created by the worker on first login —
nothing to pre-create unless you want a CMK; see Step 5.)

## Step 3 — Provision queues + service (CloudShell)
```bash
source <(./scripts/prod-infra-setup.sh --print-env)        # SUBNET_ID, SG_ID, roles, NS_ARN
./scripts/prod-infra-setup.sh xero bankstatement prod-<sha> # tag from Step 1
```
This creates: `prod-financeagent-tasks-bankstatement-xero` (DLQ-backed),
`prod-financeagent-bankstatement-results`, the log group, the ARM64 task def
(`PORTAL_ID=xero`, `XERO_BROWSER_CHANNEL=""`, `FINANCEAGENT_CREDENTIALS=aws`, prefix=prod), and
the ECS service running the Step-1 image (desired-count 1). It prints the exact secret/IAM steps.

## Step 4 — Grant the task role IAM
Add to the worker task role (the script echoes the exact ARNs):
- `secretsmanager:GetSecretValue` on `…:secret:prod/financeagent/shared/portals/xero-*`
- `secretsmanager:GetSecretValue,PutSecretValue,CreateSecret,DeleteSecret` on
  `…:secret:prod/financeagent/sessions/xero-*`
- `kms:Encrypt,Decrypt,GenerateDataKey` on the session KMS key **only if** you set a CMK (Step 5)
- `sqs:ReceiveMessage,DeleteMessage,GetQueueAttributes` on the task-queue ARN
- `sqs:SendMessage` on the results-queue ARN
- `s3:PutObject` on the artifacts bucket (already granted if other portals run)

## Step 5 — (Optional) session encryption + pre-seed
- **CMK (optional):** to encrypt the session secret with a customer-managed key, set
  `FINANCEAGENT_SESSION_KMS_KEY_ID=<keyId>` in the xero env block of `prod-infra-setup.sh` and
  re-run it. Default (omitted) = Secrets Manager's AWS-managed key, which is fine.
- **Pre-seed (optional, recommended for a clean first run):** run `SessionSeeder` against prod
  (`FINANCEAGENT_CREDENTIALS=aws`, `FINANCEAGENT_SECRETS_ENV_PREFIX=prod`, AWS creds, a headed
  login that ticks "trust this device"). This writes `prod/financeagent/sessions/xero` so the
  first prod run skips 2FA. **If you skip seeding**, the first run cold-logs-in and exercises the
  autonomous TOTP path — which is exactly the in-prod 2FA test (see Step 7).

## Step 6 — Confirm the worker is up
```bash
aws ecs describe-services --cluster financeagent \
  --services financeagent-worker-xero --query 'services[0].{desired:desiredCount,running:runningCount}'
aws logs tail /ecs/financeagent-worker-xero --follow      # expect: BankStatementTaskListener registered
```
If a scale-to-zero schedule applies, make sure desired-count ≥ 1 while testing.

## Step 7 — Test (E2E with Praxis)
The clean end-to-end test is **Praxis publishing a real request** to
`prod-financeagent-tasks-bankstatement-xero` (CSV with the exact header template
`*Date,*Amount,Payee,Description,Reference,Check Number`; see PraxisCoordinationNote.md). Then:

1. **Tail logs** (`aws logs tail … --follow`) — watch login → org switch → account match → import.
2. **Result** — a message lands on `prod-financeagent-bankstatement-results` with the **same
   businessKey**; `status=SUCCESS`. Praxis routes it (archive vs HITL).
3. **Duplicate path** — re-publish the same statement → `status=MISMATCH`,
   `errorCategory=DUPLICATE_STATEMENT` → Praxis routes to HITL (not DLQ).
4. **2FA path** — if not pre-seeded (Step 5), the first run hits the TOTP challenge. Expected:
   the worker fills the code, ticks "trust this device", proceeds. **If a 2FA selector is off**,
   the run fails AUTH and dumps `2fa-challenge.html` + `.png` to
   `s3://prod-financeagent-artifacts/prod/runs/xero/<runId>/` — fetch it and we harden the
   `XERO_2FA_*` selectors in one pass, then redeploy (push to main).
4. **Artifacts** — every run uploads `manifest.json`, `report.png`, `network.har`, `trace.zip`,
   the result envelope, and any debug dumps to S3 under `prod/runs/xero/<runId>/`.

### NeoProc-only smoke test (without Praxis)
To exercise the worker alone, hand-publish one request envelope to the task queue
(`aws sqs send-message --queue-url <task-queue> --message-body file://request.v1.json` with a
valid `bank-statement-upload-request.v1`, businessKey, and base64 CSV inline). Watch the same
logs/results/artifacts. (Idempotency: a re-delivered businessKey on the cold path emits a
synthetic FAILED without re-executing — don't reuse a succeeded key for a fresh test.)

---

## Rollback / re-deploy
- Code change → push to `main`; the deploy job rebuilds and updates the `xero` service in place
  (env vars preserved).
- Bad image → `aws ecs update-service --cluster financeagent --service financeagent-worker-xero
  --task-definition <previous-TD-arn> --force-new-deployment`.
- Stuck session → delete `prod/financeagent/sessions/xero` (forces a fresh cold login) or re-seed.
