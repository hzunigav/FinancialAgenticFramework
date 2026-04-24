# Infra Setup — NeoProc-operated Phase 1 work

Three tracks the user owns directly for Phase 1 integration. These run in parallel and block nothing on the Praxis side until end-to-end tests begin. The [PraxisIntegrationHandoff.md](PraxisIntegrationHandoff.md) assumes all three are done.

**What Praxis is waiting on from us:**
- Track A — so they can pull the `contract-api` dependency.
- Track B — so their `KmsEnvelopeClient` and credentials provider have IAM permissions to use KMS + Secrets Manager.
- Track C — so a worker is actually running to consume envelopes.

---

## At a glance

| Track | What | Calendar time | Blocking? |
|---|---|---|---|
| A | GitHub Packages for `contract-api` | ~2 hours | Blocks Praxis Workstream A/B import |
| B | AWS KMS + Secrets Manager policies + IAM roles | ~half day | Blocks Workstream A (encryption) + B (credentials) end-to-end |
| C | ECS Fargate fleet for agent-workers | 2–3 days | Blocks Workstream C end-to-end test |

Sequencing: **Tracks A, B, C start in parallel.** Track C's *infrastructure stand-up* (VPC, ECR, cluster) doesn't depend on Track B. Track C's IAM task role definitions (step C.3) reference the policy shapes from Track B step B.3 — finish B.3 before C.3, otherwise fully parallel.

---

## Track A — GitHub Packages for `contract-api`

**Goal:** Publish `contract-api-1.0.0-SNAPSHOT.jar` to GitHub Packages so Praxis's Maven build can pull it as a dependency.

### A.1 Repo + registry decision

- [ ] Confirm the GitHub org/repo that will host the package. Recommend the repo where `contract-api/` lives (this one). Packages inherit the repo's visibility (private vs public).
- [ ] Decide package visibility. If the repo is private, the packages are private and consumers need an authenticated token.

### A.2 Configure publishing in Maven

- [ ] Add `distributionManagement` to the parent `pom.xml`:
  ```xml
  <distributionManagement>
    <repository>
      <id>github</id>
      <name>GitHub Packages</name>
      <url>https://maven.pkg.github.com/<org>/<repo></url>
    </repository>
    <snapshotRepository>
      <id>github</id>
      <url>https://maven.pkg.github.com/<org>/<repo></url>
    </snapshotRepository>
  </distributionManagement>
  ```
- [ ] Add a `server` entry to `~/.m2/settings.xml` for local publishing:
  ```xml
  <server>
    <id>github</id>
    <username><your-github-username></username>
    <password>${env.GITHUB_PACKAGES_TOKEN}</password>
  </server>
  ```

### A.3 Tokens

- [ ] **Publisher token (you):** create a classic PAT with `write:packages`, `read:packages`, `repo` scopes. Export as `GITHUB_PACKAGES_TOKEN` in your shell before running `mvn deploy`.
- [ ] **Consumer token (Praxis):** Praxis's CI needs a token with `read:packages` only. Prefer an Actions-issued `GITHUB_TOKEN` in their workflow over a long-lived PAT. Document the exact `settings.xml` snippet they need in the reply to Praxis.

### A.4 First publish

- [ ] From the repo root: `mvn -pl contract-api -am deploy`.
- [ ] Verify the package lands under the repo's "Packages" tab on GitHub.

### A.5 Automate via GitHub Actions

- [ ] Add a `.github/workflows/publish-contract-api.yml` that runs on push to `main` when `contract-api/**` changes. Uses the default `GITHUB_TOKEN`; no PAT needed.
- [ ] Gate on `mvn -pl contract-api test` passing first.

### A.6 Acceptance

- [ ] `mvn deploy` run locally lands a `1.0.0-SNAPSHOT` in GitHub Packages.
- [ ] A throwaway test project on Praxis's side can pull the dependency with a `read:packages` token.
- [ ] CI auto-publishes on merges to main that touch `contract-api/`.

**Rough cost:** $0 (included in GitHub plan). Storage counts against the org's Packages quota; a Maven jar is trivially small.

---

## Track B — AWS KMS + Secrets Manager

**Goal:** Permissions and policies in place so Praxis can provision per-firm KMS keys (envelope encryption) and write/read portal credentials in Secrets Manager. The agent-worker reads from both via its ECS task role.

This replaces the earlier Vault-based design. KMS provides the per-firm encryption primitive (same role as Vault's `transit` engine); Secrets Manager holds portal credentials at the same path layout originally planned for Vault `kv-v2`. IAM task roles replace AppRole authentication — no token lifecycle, no `secret_id` to rotate.

**Why the change:** at our scale HCP Vault Plus is ~$700–1400/mo; KMS + Secrets Manager covers the same primitives at ~$15–90/mo and removes a managed-service dependency. See [CONTRACT.md §4](contract-api/CONTRACT.md#4-encryption) for the full encryption spec.

### B.1 KMS — no upfront keys

- [ ] Pick the AWS account + region. Recommend the same region Fargate runs in (Track C) to avoid cross-region KMS charges.
- [ ] **Per-firm KMS keys are created by Praxis at firm onboarding** (see CONTRACT.md §4 production runbook), not provisioned upfront. This track only sets up the *permissions to create and use them*.
- [ ] Identify or create the IAM admin principal that will manage KMS infrastructure (your Terraform/CDK deployment role, or a dedicated `financeagent-admin` role).

### B.2 Secrets Manager — no upfront secrets

Layout (mirrors the original plan):
```
financeagent/firms/<firmId>/portals/<portalId>     # per-firm portal creds
financeagent/shared/portals/<portalId>             # shared (tenant-level) portal creds
financeagent/rabbitmq/connection                   # broker URL — Track C reads this
```
Each portal secret holds `{ "username", "password", "mfaMethod", "mfaSecret" }`.

- [ ] **Nothing to provision upfront** — Secrets Manager has no equivalent of Vault's mount step; the resource path *is* the secret. Secrets are created on demand by Praxis at firm onboarding (per-firm) or tenant setup (shared).
- [ ] Encryption: each secret uses the AWS-managed KMS key `aws/secretsmanager` by default — fine for Phase 1. Per-secret CMKs are a post-v1 tightening.
- [ ] Create `financeagent/rabbitmq/connection` once with the broker URL — needed by Track C's task definition.

### B.3 IAM roles

Three principals need permissions: the Praxis service role, the worker task role (per portal — Track C), and the onboarding admin (you).

**Praxis service role** (your Spring Boot runtime — wherever Praxis runs):
- [ ] `kms:CreateKey`, `kms:CreateAlias`, `kms:ListAliases`, `kms:DescribeKey`, `kms:EnableKeyRotation` on `*` (per-firm key provisioning on `FirmCreatedEvent`).
- [ ] `kms:Encrypt`, `kms:GenerateDataKey`, `kms:Decrypt` on keys tagged with any `firmId` value (envelope encrypt/decrypt).
- [ ] `secretsmanager:CreateSecret`, `PutSecretValue`, `UpdateSecret`, `GetSecretValue`, `DescribeSecret` on `arn:aws:secretsmanager:*:*:secret:financeagent/*`.
- [ ] If Praxis runs in a different AWS account, set up a cross-account role here with a trust policy that allows Praxis's account/role to assume it. Otherwise (same account) just attach the policy to Praxis's existing service role.

**Worker task role** (one per portal, defined in Track C.3):
- [ ] `kms:Decrypt` only — never encrypt — on keys tagged with any `firmId`.
- [ ] `secretsmanager:GetSecretValue` on `arn:aws:secretsmanager:*:*:secret:financeagent/firms/*/portals/*` **and** `arn:aws:secretsmanager:*:*:secret:financeagent/shared/portals/*` **and** `arn:aws:secretsmanager:*:*:secret:financeagent/rabbitmq/connection`.

**Worker policy sample:**
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "kms:Decrypt",
      "Resource": "arn:aws:kms:*:*:key/*",
      "Condition": { "StringLike": { "aws:ResourceTag/firmId": "*" } }
    },
    {
      "Effect": "Allow",
      "Action": "secretsmanager:GetSecretValue",
      "Resource": [
        "arn:aws:secretsmanager:*:*:secret:financeagent/firms/*/portals/*",
        "arn:aws:secretsmanager:*:*:secret:financeagent/shared/portals/*",
        "arn:aws:secretsmanager:*:*:secret:financeagent/rabbitmq/connection*"
      ]
    }
  ]
}
```

### B.4 Audit

- [ ] All KMS `Encrypt`/`Decrypt`/`GenerateDataKey` and Secrets Manager `GetSecretValue` calls are logged to **CloudTrail** automatically — no extra config.
- [ ] Confirm your CloudTrail trail's S3 bucket retains for the Costa Rica audit window (1 year minimum).
- [ ] Optional: save a CloudWatch Log Insights query for "every envelope decrypt for firm X in period Y" so audit responses are templated, not improvised.

### B.5 Acceptance

- [ ] As the **Praxis service role**:
  ```bash
  aws kms create-key --description "test" --tags TagKey=firmId,TagValue=test
  aws kms create-alias --alias-name alias/payroll-firm-test --target-key-id <id>
  aws kms generate-data-key --key-id alias/payroll-firm-test --key-spec AES_256
  aws secretsmanager create-secret --name financeagent/firms/test/portals/mock-payroll \
    --secret-string '{"username":"u","password":"p"}'
  ```
  All succeed.
- [ ] As the **worker task role** (via `aws sts assume-role`):
  ```bash
  aws kms decrypt --ciphertext-blob fileb://<edk-from-above>
  aws secretsmanager get-secret-value --secret-id financeagent/firms/test/portals/mock-payroll
  ```
  Both succeed.
- [ ] Worker task role's `kms:Encrypt` attempt **fails** with `AccessDenied`.
- [ ] Worker task role's `secretsmanager:PutSecretValue` attempt **fails**.
- [ ] CloudTrail shows the decrypt + GetSecretValue events within 15 minutes.

**Rough cost:**
- KMS: $1/mo per customer-managed key + ~$0.01 per 10k API calls. **~$10/mo at 10 firms, ~$50/mo at 50 firms.**
- Secrets Manager: $0.40/mo per secret + ~$0.05 per 10k API calls. **~$5/mo at 10 firms, ~$25/mo at 50 firms.**
- CloudTrail: typically $0 incremental (your existing trail covers it).
- **Total: ~$15–90/mo depending on firm count.** No managed-Vault line.

---

## Track C — ECS Fargate fleet for agent-workers

**Goal:** One Fargate service per portal (`ccss`, `ins`, `hacienda`, `autoplanilla`, `mock-payroll`), each consuming from its RabbitMQ queue.

### C.1 VPC + networking

- [ ] Identify the VPC + private subnets Fargate tasks run in. **Recommend sharing the same VPC as Praxis** to simplify RabbitMQ reachability. If Praxis doesn't have a shared-VPC story, stand up a dedicated `financeagent` VPC and VPC-peer with Praxis's VPC for the Rabbit hop.
- [ ] Ensure NAT Gateway (or VPC endpoints) provides egress for:
  - Government portals (CCSS / INS / Hacienda via NAT — static EIP needed for whitelisting).
  - AWS services: ECR, Secrets Manager, KMS, CloudWatch Logs, S3 (prefer VPC endpoints for all five — KMS + Secrets Manager endpoints save NAT data charges and avoid routing IAM traffic over the public internet).
  - RabbitMQ broker (peered VPC or shared VPC).
- [ ] **Static NAT EIP** — required for Phase 6 "IP whitelisting" per [DeploymentPlan.md §6](DeploymentPlan.md). Allocate now; Praxis team will forward it to CCSS / INS / Hacienda admins later.

### C.2 ECR

- [ ] Create one ECR repository: `financeagent/agent-worker`. Lifecycle policy: keep last 20 images.
- [ ] Enable image scanning on push.

### C.3 IAM

- [ ] **Task execution role** (`financeagent-worker-exec`) — ECR pull, CloudWatch Logs write. AWS-managed policy `AmazonECSTaskExecutionRolePolicy` covers both.
- [ ] **Task role per portal** (`financeagent-worker-<portal>-task`) — scoped permissions per [Track B.3](#b3-iam-roles):
  - `kms:Decrypt` on KMS keys tagged with any `firmId` value.
  - `secretsmanager:GetSecretValue` on `financeagent/firms/*/portals/*`, `financeagent/shared/portals/*`, and `financeagent/rabbitmq/connection`.
  - `s3:PutObject` on `s3://financeagent-artifacts/<portal>/*`.
  - No KMS `Encrypt`, no Secrets Manager writes, no other permissions.

### C.4 S3 artifact bucket

- [ ] Create `financeagent-artifacts` bucket. Versioning on. Default encryption: KMS (customer-managed key).
- [ ] **Object Lock + WORM policy** for 7-year retention per [DeploymentPlan.md §1](DeploymentPlan.md#phase-1-infrastructure-provisioning-aws). Legal to confirm the exact retention (Costa Rica financial records — likely 1 year minimum, but WORM-7 is the existing standard in the plan).
- [ ] Lifecycle: transition to Glacier Deep Archive after 90 days; no expiration during the legal-hold window.

### C.5 Security group

- [ ] `financeagent-worker-sg` — no ingress. Egress allows:
  - 5671 to RabbitMQ.
  - 443 to the world (government portals through NAT; AWS API calls to KMS / Secrets Manager / S3 / ECR / CloudWatch also use 443, ideally via VPC endpoints rather than NAT).

### C.6 Container image

- [ ] Author `agent-worker/Dockerfile`:
  ```
  FROM mcr.microsoft.com/playwright/java:v1.48.0-jammy AS runtime
  COPY agent-worker/target/agent-worker-*-jar-with-dependencies.jar /app/agent-worker.jar
  ENTRYPOINT ["java", "-jar", "/app/agent-worker.jar"]
  ```
- [ ] CI pipeline: build fat jar → build image → tag with commit SHA + `latest` → push to ECR. Add this as a new workflow in `.github/workflows/`.

### C.7 ECS cluster + services

- [ ] Create ECS cluster `financeagent-workers`.
- [ ] Per-portal task definitions. Shape:
  ```
  family: financeagent-worker-<portal>
  cpu: 1024
  memory: 2048
  networkMode: awsvpc
  executionRoleArn: financeagent-worker-exec
  taskRoleArn: financeagent-worker-<portal>-task
  containerDefinitions:
    - name: worker
      image: <account>.dkr.ecr.<region>.amazonaws.com/financeagent/agent-worker:<tag>
      environment:
        - { name: PORTAL_ID, value: <portal> }
        - { name: FINANCEAGENT_CIPHER, value: kms }
        - { name: FINANCEAGENT_CREDENTIALS, value: aws }
        - { name: AWS_REGION, value: <region> }
      secrets:
        - { name: RABBITMQ_URI, valueFrom: arn:...:financeagent/rabbitmq/connection }
      logConfiguration:
        logDriver: awslogs
        options:
          awslogs-group: /ecs/financeagent-worker-<portal>
          awslogs-region: <region>
          awslogs-stream-prefix: worker
  ```
- [ ] Per-portal ECS services — `desiredCount: 1` initially. Launch type `FARGATE`. Placement in the private subnets from C.1 with the security group from C.5.
- [ ] CloudWatch Container Insights: on.

### C.8 Acceptance

- [ ] `mock-payroll` service boots, authenticates to AWS via its ECS task role, logs "ready to consume" on its queue.
- [ ] Publishing a `payroll-submit-request.v1` envelope to `financeagent.tasks.submit.mock-payroll` results in a `payroll-submit-result.v1` on `financeagent.results` within 60s.
- [ ] Audit: the run's manifest + HAR + screenshot land under `s3://financeagent-artifacts/mock-payroll/<runId>/`.
- [ ] Worker logs reach CloudWatch with the run's `envelopeId` / `businessKey` / `issuerRunId` grep-able.

**Rough cost (all 5 services at desiredCount=1):**
- Fargate compute: ~$85/mo (1 vCPU + 2 GB × 5 tasks × 24h × 30d).
- ECR storage: ~$5/mo.
- S3 artifacts: depends on run volume; ~$5–20/mo initially.
- NAT Gateway: ~$32/mo + data.
- CloudWatch Logs: ~$10/mo at modest volumes.
- **Total: ~$140–170/mo steady-state.** KMS + Secrets Manager (Track B) adds ~$15–90/mo depending on firm count.

---

## Green-light checklist for Phase 1 dev start

Once these boxes are ticked, the handoff blockers from [PraxisIntegrationHandoff.md §11](PraxisIntegrationHandoff.md#11-open-questions-please-confirm-or-redirect) are cleared and Praxis can move past design into implementation on their side:

- [ ] Track A.6 acceptance passes — Praxis has pulled `contract-api` at least once.
- [ ] Track B.5 acceptance passes — both the Praxis service role and the worker task role work with the intended scope (positive and negative tests).
- [ ] Track C.8 acceptance passes — a mock-payroll round-trip lands end-to-end.
- [ ] If Praxis runs in a separate AWS account: cross-account IAM role ARN handed off to Praxis (with the policy from B.3 attached). If same account: confirm Praxis's existing service role has the B.3 policy attached.
- [ ] RabbitMQ connection string written to `financeagent/rabbitmq/connection` and the secret ARN shared with Praxis.
- [ ] Static NAT EIP documented for later whitelisting.

---

## What's explicitly *not* in this doc

- **Praxis-side work** (BPMN wiring, `KmsEnvelopeClient`, firm onboarding UI, HITL task descriptions) — tracked in [PraxisIntegrationHandoff.md](PraxisIntegrationHandoff.md).
- **Portal descriptor work** (CCSS / INS / Hacienda YAML + adapters) — Phase 2, tracked in [ImplementationPlan.md](ImplementationPlan.md).
- **Governance / compliance sign-off** — [DeploymentPlan.md §6](DeploymentPlan.md) handles pre-go-live review.

Keep this doc updated as tracks land. When a track is fully green, strike through the checklist and leave a one-line "shipped <date> — <commit>" note at the bottom of that track's section.
