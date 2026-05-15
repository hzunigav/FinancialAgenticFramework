# Deployment Plan: Financial Agentic Framework v3.0

Living document. Complements [ImplementationPlan.md](ImplementationPlan.md) (code milestones)
and [SqsMigrationPlan.md](SqsMigrationPlan.md) (SQS transport detail + §10 checklist).

**Legend:** ✓ done · → next · · planned

---

## Current status (2026-05-14)

| Item | Status |
|---|---|
| Praxis SQS code (Spring Cloud AWS, listeners, BPMN timers) | ✓ |
| Agent-worker SQS code (@SqsListener, SqsRetryablePublisher, idempotency upgrade) | ✓ |
| Dev AWS resources provisioned (8 queues, KMS key, mock secrets, PraxisDeveloperLocal policy) | ✓ |
| Dev parity test — mock-payroll full capture + submit on real dev queues, BPMN advanced | ✓ |
| Prod AWS resources provisioned | → |
| Prod cutover (EB env var flip, smoke cycle) | · |
| First real CCSS / INS cycle in prod | · |

---

## Phase 1 — Prod AWS Resource Provisioning

**Objective:** Create the eight production SQS queues, the NeoProc KMS key, and the
IAM policies that gate prod access. Follows the naming convention locked in
[SqsMigrationPlan.md §3](SqsMigrationPlan.md) — `<env>-financeagent-<role>[-<portal-id>]`.

### 1.1 SQS queues (§4.1)

Create DLQ first, then the seven working queues referencing its ARN in the redrive policy.

**Queue list:**
```
prod-financeagent-dlq                          (14-day retention, no redrive)
prod-financeagent-tasks-capture-mock-payroll
prod-financeagent-tasks-capture-autoplanilla
prod-financeagent-tasks-submit-ccss-sicere
prod-financeagent-tasks-submit-ins-rt-virtual
prod-financeagent-tasks-submit-hacienda-ovi
prod-financeagent-tasks-submit-mock-payroll
prod-financeagent-results
```

Working queue attributes: `VisibilityTimeout=900`, `ReceiveMessageWaitTimeSeconds=20`,
`MessageRetentionPeriod=345600`, `maxReceiveCount=5` → DLQ.

### 1.2 KMS key — NeoProc manual backfill (§4.2)

`KmsKeyProvisioningListener` does not backfill existing firms. Provision manually:

```bash
aws kms create-key --region us-east-1 \
  --description "Payroll envelope key — firm 1 (prod)" \
  --key-usage ENCRYPT_DECRYPT --key-spec SYMMETRIC_DEFAULT \
  --tags TagKey=Environment,TagValue=prod TagKey=Project,TagValue=praxis TagKey=firmId,TagValue=1

aws kms create-alias --region us-east-1 \
  --alias-name alias/prod-payroll-firm-1 --target-key-id <KeyId>
aws kms enable-key-rotation --region us-east-1 --key-id <KeyId>
```

Future firms are provisioned automatically on `FirmCreatedEvent`.

### 1.3 IAM policies (§3.6 / §4.4)

| Policy | Action | Attached to |
|---|---|---|
| `PraxisSqsFinanceagent` | NEW — SQS ops on `prod-financeagent-*` | EB instance profile |
| `PraxisKmsEnvelope` | UPDATE — change alias constraint to `alias/prod-payroll-firm-*` | EB instance profile |
| `PraxisSecretsManagerFinanceagent` | UPDATE — change resource to `prod/financeagent/*` | EB instance profile |
| `AgentWorkerSqsFinanceagent` | NEW — consume tasks queues, send to results queue | Fargate task role |
| `AgentWorkerSecretsFinanceagent` | NEW — `GetSecretValue` on `prod/financeagent/firms/*/portals/*` and `prod/financeagent/shared/portals/*` | Fargate task role |
| `AgentWorkerKmsDecrypt` | DEFERRED — created when envelope encryption is enabled (see EnhancementsBacklog) | Fargate task role |

Policy JSON skeletons live in [SqsMigrationPlan.md §4.4](SqsMigrationPlan.md).

### 1.4 Secrets Manager — prod credentials (§4.3)

Real CCSS / INS / Hacienda credentials are created on the day of the first real cycle,
not in advance. Path convention:

- Per-firm/client: `prod/financeagent/firms/<firmId>/portals/<portalId>/<corporateId>`
- Shared: `prod/financeagent/shared/portals/<portalId>`

### 1.5 Tagging

Every resource at creation: `Environment=prod`, `Project=praxis`, `Component=financeagent`, `ManagedBy=manual`.

---

## Phase 2 — Agent-Worker Fargate Deployment

**Objective:** Build the prod Docker image and run one Fargate service per portal.

### 2.1 ECR repository

One ECR repo: `financeagent-worker`. Image tagged `<portalId>-<git-sha>`.

### 2.2 Docker image

Multi-stage build already exists at [Dockerfile](../Dockerfile):
- Build stage: `maven:3.9-eclipse-temurin-21-jammy`
- Runtime stage: `mcr.microsoft.com/playwright/java:v1.48.0-jammy` (Chromium/Firefox/WebKit included)

Build and push:
```bash
docker build -t financeagent-worker .
docker tag financeagent-worker <acct>.dkr.ecr.us-east-1.amazonaws.com/financeagent-worker:<tag>
docker push <acct>.dkr.ecr.us-east-1.amazonaws.com/financeagent-worker:<tag>
```

### 2.3 Fargate services

One ECS service per active portal. All run in the private subnet (NAT Gateway egress
for portal HTTPS; VPC Endpoints for SQS, KMS, Secrets Manager — no public internet
for internal AWS calls).

| Service name | `PORTAL_ID` env var | Subscribes to |
|---|---|---|
| `financeagent-worker-mock-payroll` | `mock-payroll` | tasks-capture-mock-payroll + tasks-submit-mock-payroll |
| `financeagent-worker-autoplanilla` | `autoplanilla` | tasks-capture-autoplanilla |
| `financeagent-worker-ccss-sicere` | `ccss-sicere` | tasks-submit-ccss-sicere |
| `financeagent-worker-ins-rt-virtual` | `ins-rt-virtual` | tasks-submit-ins-rt-virtual |
| `financeagent-worker-hacienda-ovi` | `hacienda-ovi` | tasks-submit-hacienda-ovi |

**Task definition env vars (injected at task-definition time):**
```
PORTAL_ID=<portal-id>
AWS_REGION=us-east-1
FINANCEAGENT_CIPHER=cleartext    # encryption deferred; flip to 'kms' when enabled
FINANCEAGENT_CREDENTIALS=aws
agent.worker.queue-prefix=prod
```

AWS credentials come from the ECS task role (IAM, no static keys).

---

## Phase 3 — Praxis Cutover

**Objective:** Flip Praxis from whatever transport it currently uses to the prod SQS queues.

### 3.1 Pre-cutover checklist

- [ ] All Phase 1 AWS resources provisioned and smoke-verified (`aws sqs list-queues --queue-name-prefix prod-financeagent` returns 8 entries)
- [ ] All Phase 2 Fargate services running and healthy (ECS console, no stopped tasks)
- [ ] Boot logs confirm SQS listeners started on each worker, no startup errors
- [ ] RDS snapshot taken

### 3.2 Cutover

Set EB environment variable: `PRAXIS_SQS_ENABLED=true`

EB redeploys automatically. `application-aws.yml` picks up `PRAXIS_SQS_ENABLED=true` and
`queue-prefix=prod`, so SQS listener and publisher beans activate on next boot.

### 3.3 Smoke test — mock-payroll prod cycle

Trigger a mock-payroll cycle from the Praxis admin UI. Verify:
- Round-trip completes (BPMN Receive Tasks advance)
- Audit bundle written
- No DLQ messages (`aws sqs get-queue-attributes --attribute-names ApproximateNumberOfMessages`)
- `/actuator/prometheus` on the worker exposes the four spec metrics

### 3.4 IP whitelisting

Coordinate with CCSS / INS / Hacienda portal administrators to whitelist the Static
Elastic IP of the NAT Gateway before the first real cycle.

---

## Phase 4 — First Real Cycle

**Objective:** Run the first supervised real payroll cycle (CCSS / INS, one client firm).

### 4.1 Secrets

Load real portal credentials into Secrets Manager for the client under test immediately before the cycle.

### 4.2 Supervised run

Trigger from the Praxis admin UI with a senior operator watching worker logs in real time.
Have rollback ready: cancel the Praxis workflow and fall back to manual portal submission.

---

## Phase 5 — Observability & Hardening (post-cycle)

Items from [SqsMigrationPlan.md §3.7](SqsMigrationPlan.md) and [EnhancementsBacklog.md](EnhancementsBacklog.md) to land after the first successful real cycle:

- **CloudWatch alarms**: DLQ depth > 0 for 5 min → page; result queue age > 600s → warn; per-submit-queue age > 600s → warn (stuck worker).
- **Visibility timeout calibration**: re-verify the 900s baseline holds against real-portal P95 timings from the first cycle (per §6.6 Q3 watch clause).
- **Envelope size monitoring**: flag any single envelope > 180 KB cleartext (§6.6 Q7 watch threshold) — triggers Option B (SQS Extended Client) evaluation.

---

## Deferred — M6 Full Production Hardening

The following items are planned for M6 (post Phase 11D go-live) and are not
blocking the current cutover:

| Item | Notes |
|---|---|
| VPC + private subnets + NAT Gateway + VPC Endpoints via CDK | Assumed to already exist in the NeoProc account; CDK module goes in `infra-aws` |
| S3 bucket with 7-year WORM policy for audit bundles | Currently written to local filesystem |
| DynamoDB execution-state checkpoint table | Currently `InMemoryIdempotencyStore` (Caffeine, process-local) |
| Envelope encryption (`FINANCEAGENT_CIPHER=kms`) | Requires `ciphertextField` schema fix first; `AgentWorkerKmsDecrypt` policy is the named precondition |
| GraalVM native image | Explicit M6 item; current runtime is standard JVM on `playwright/java` |
| IaC (Terraform / CDK) for SQS queues, KMS, IAM | Currently manual; tracked in EnhancementsBacklog |
| OpenTelemetry → AWS X-Ray | Currently SLF4J/MDC + Prometheus metrics |
| Redis/DynamoDB-backed idempotency store | Persistent restart survival; Caffeine is sufficient for v1 volumes |
