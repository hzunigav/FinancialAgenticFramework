# SQS Migration Plan — Phase 11D Transport Switch

> **Status:** DEV PARITY VALIDATED 2026-05-14 — mock-payroll full capture + submit cycle
> confirmed end-to-end on real `dev-financeagent-*` queues with Praxis BPM advancing
> through both `waitCaptureResult` and `waitSubmitResult`. Remaining work: AWS prod
> resource provisioning (§10 AWS resources — PROD) and prod cutover steps (§10 Cutover).
>
> **Owner:** Praxis team (this doc) + Agent-worker team (their half of §6 + §10).
>
> **Created:** 2026-05-05. **Locked:** 2026-05-06.
>
> **Supersedes:** the Amazon MQ pieces of [Phase8SliceProvisioning.md §3](Phase8SliceProvisioning.md#3-amazon-mq-rabbitmq-broker).
> Once this plan ships, those sections of the runbook are removed.

---

## 1. Context & Decision

### What we're switching

Replace Amazon MQ (RabbitMQ) with AWS SQS as the transport between Praxis and
the agent-worker for Phase 11D payroll envelopes.

### Why

| Dimension                     | Amazon MQ (mq.m7g.medium single-instance) | AWS SQS                  |
| ----------------------------- | ----------------------------------------- | ------------------------ |
| Direct cost (us-east-1)       | ~$80/mo, ~$960/yr                         | ~$1/mo, ~$12/yr          |
| Ops surface                   | Broker patching, version upgrades, maintenance windows, SG hygiene | None — fully managed     |
| Architectural fit             | Outlier: only "VM-like" service in our AWS-native stack | Native: matches KMS, Secrets Manager, RDS, EB |
| Provisioning time             | ~20 min broker spin-up                    | Instant (queue create)   |
| DLQ setup                     | Separate exchange + binding + queue + listener | 2-line redrive policy   |

### What we lose by switching

- AMQP semantics (exchange/routing-key, `correlation-id`/`reply-to` headers, native ack/nack). We map them onto SQS equivalents in §6.
- The RabbitMQ Management UI for ad-hoc envelope replay. Replaced by `aws sqs send-message`.
- Spring AMQP `@RabbitListener` ergonomics. Replaced by Spring Cloud AWS `@SqsListener` (similar but not identical).

### Why now (vs. later)

Decided **before** Phase 11D goes live in prod. After cycle-1 the cost of
switching includes coordinating a downtime window with the agent-worker, a
data-migration story for in-flight envelopes on the broker, and rebuilding
operational muscle memory. Cleaner to switch before any prod cycle has run.

### Constraints

- **No staging environment.** Two-tier dev strategy compensates: LocalStack for
  inner loop, real AWS dev-prefixed resources for pre-prod parity (§7).
- **Single-tenant prod (NeoProc only).** Acceptable blast radius if the cutover
  has issues — we're not impacting other customers.
- **Agent-worker team must do their half.** ~3–5 days on their side. Plan is
  **gated** on their sign-off in §6.

---

## 2. Goals & Non-Goals

### Goals

- Single transport (SQS) across all environments.
- Zero broker ops surface.
- Naming convention strict enough that a misconfigured dev consumer **cannot**
  reach prod queues (different ARN, IAM permission boundary).
- Local dev unchanged in spirit: `docker compose up` and you're working.
- Existing wire format (envelope schemas, encryption, schema validation) **unchanged**.
- The `contract-api` JAR **unchanged** — wire format is the body, transport
  adapter is in service code.

### Non-Goals

- Changing the envelope schema or BPMN process model.
- Switching to FIFO queues (we're idempotent at the application layer via
  `envelopeId` + agent-side `InMemoryIdempotencyStore` — Standard queues are
  correct and cheaper).
- Multi-region replication (single-region us-east-1 stays).
- IaC (Terraform / CDK) — manual provisioning for now, IaC is a separate
  follow-up tracked in `docs/EnhancementsBacklog.md`.

---

## 3. AWS Resource & Naming Conventions [REFERENCE — use this when provisioning]

This is the section to bookmark when you're in the AWS console creating resources.
Every name follows a deterministic pattern so you can derive it without looking
anything up.

### 3.1 Account & region

- **Single AWS account** for both dev and prod resources (NeoProc is a one-account shop).
- **Region:** `us-east-1` exclusively. No multi-region.
- **Isolation between dev and prod is enforced by name prefix + IAM, not by account.**

### 3.2 Environment prefix convention

**Always explicit, no implicit prod.** This is the most important rule. The
single-account-no-staging incident pattern is "I forgot to prefix and accidentally
read/wrote prod." Forcing every environment to declare itself in the name + ARN
makes that mistake impossible at the IAM layer.

| Environment | Prefix     | Used by                                                   |
| ----------- | ---------- | --------------------------------------------------------- |
| `dev`       | `dev-`     | Local developer laptops + LocalStack-replacing parity tests |
| `prod`      | `prod-`    | EB-deployed Praxis + Fargate-deployed agent-worker        |

There is no `staging` prefix because there's no staging environment. If one is
added later, it becomes `staging-`.

### 3.3 SQS queue naming

**Pattern:** `<env>-financeagent-<role>[-<portal-id>]`

| Component   | Allowed values                                                                                |
| ----------- | --------------------------------------------------------------------------------------------- |
| `<env>`     | `dev` \| `prod`                                                                                |
| `financeagent` | Literal — the system name (matches existing convention; future systems get their own segment) |
| `<role>`    | `tasks-capture` \| `tasks-submit` \| `results` \| `dlq`                                       |
| `<portal-id>` | Required for `tasks-capture` and `tasks-submit` only. Values: `mock-payroll`, `ccss-sicere`, `ins-rt-virtual`, `hacienda-ovi`, `autoplanilla` |

**Constraints from AWS:** SQS queue names are alphanumeric + hyphens + underscores,
max 80 chars, no dots (the dots in `financeagent.tasks.capture.X` from the
RabbitMQ topology must become hyphens). Longest name in our convention:
`prod-financeagent-tasks-submit-hacienda-ovi` = 43 chars — comfortably under the limit.

**Full prod queue list (8 queues):**

```
prod-financeagent-tasks-capture-mock-payroll
prod-financeagent-tasks-capture-autoplanilla
prod-financeagent-tasks-submit-ccss-sicere
prod-financeagent-tasks-submit-ins-rt-virtual
prod-financeagent-tasks-submit-hacienda-ovi
prod-financeagent-tasks-submit-mock-payroll
prod-financeagent-results
prod-financeagent-dlq
```

**Full dev queue list (8 queues):** same as above, swap `prod-` → `dev-`.

**Queue type:** SQS **Standard** for all queues. Reasoning:
- Application-level idempotency via `envelopeId` already handles at-least-once.
- Throughput is trivial (~10 msg/cycle) — FIFO's 300/sec cap is irrelevant.
- Standard is cheaper and has no `.fifo` suffix complication.

### 3.4 KMS key & alias naming

**Pattern:** `alias/<env>-payroll-firm-<firmId>`

Examples:
- Prod NeoProc key: `alias/prod-payroll-firm-1`
- Dev NeoProc key: `alias/dev-payroll-firm-1`

**⚠️ Convention change from current code.** Today's [application-aws.yml](../src/main/resources/config/application-aws.yml#L54)
sets `alias-pattern: 'alias/payroll-firm-%d'` (no env prefix). This plan
**changes** that to `alias/prod-payroll-firm-%d` for consistency. The change is
safe because no key has been provisioned in prod yet — the
`KmsKeyProvisioningListener` only fires on `FirmCreatedEvent` and NeoProc was
created pre-Phase-11B (so the listener has never fired for it).

A separate side-task — **provision NeoProc's prod KMS key manually** since the
listener won't backfill — is tracked in §10.

### 3.5 Secrets Manager path naming

**Pattern (per-firm/per-client portals like CCSS):**
`<env>/financeagent/firms/<firmId>/portals/<portalId>/<corporateId>`

**Pattern (shared portals like INS, Hacienda, mock-payroll):**
`<env>/financeagent/shared/portals/<portalId>`

Examples:
- Prod CCSS for client 3-101-680139 under firm 1: `prod/financeagent/firms/1/portals/ccss-sicere/3-101-680139`
- Dev INS shared creds: `dev/financeagent/shared/portals/ins-rt-virtual`

**Slashes** are the Secrets Manager path convention (allowed in secret names).
The `<env>/` segment goes at the top so an IAM policy with `Resource:
"arn:...:secret:dev/financeagent/*"` cleanly excludes prod.

### 3.6 IAM policy naming

| Policy name                              | Status   | Attached to                              | What it grants                                                              |
| ---------------------------------------- | -------- | ---------------------------------------- | --------------------------------------------------------------------------- |
| `PraxisKmsEnvelope`                      | UPDATE   | EB instance profile                      | KMS envelope ops on `alias/prod-payroll-firm-*` (was `alias/payroll-firm-*`) |
| `PraxisSecretsManagerFinanceagent`       | UPDATE   | EB instance profile                      | Secrets ops on `prod/financeagent/*` (was `financeagent/*`)                 |
| `PraxisSqsFinanceagent`                  | NEW      | EB instance profile                      | SQS ops on `prod-financeagent-*` queues                                     |
| `PraxisDeveloperLocal`                   | NEW      | Your personal IAM user (`humberto-dev`)  | Full SQS/KMS/Secrets access on `dev-` / `dev/` resources only               |
| `AgentWorkerSqsFinanceagent`             | NEW      | Agent-worker Fargate task role           | SQS ops on `prod-financeagent-tasks-*` (consume) + `prod-financeagent-results` (produce) |
| `AgentWorkerSecretsFinanceagent`         | NEW      | Agent-worker Fargate task role           | `secretsmanager:GetSecretValue` on `prod/financeagent/firms/*/portals/*` and `prod/financeagent/shared/portals/*` |
| `AgentWorkerKmsDecrypt`                  | NEW      | Agent-worker Fargate task role           | `kms:Decrypt` on `alias/prod-payroll-firm-*` (for envelope decryption when encryption flips on) |

**JSON policy documents** for the new policies will live alongside the existing
ones in [infra/iam/](../infra/iam/). All `Praxis*` policies are managed
Praxis-side; all `AgentWorker*` policies are owned by the agent-worker team but
referenced here for completeness.

### 3.7 CloudWatch & observability

- **Per-queue CloudWatch metrics** are automatic (no setup): `ApproximateNumberOfMessagesVisible`,
  `ApproximateAgeOfOldestMessage`, `NumberOfMessagesSent`, etc.
- **Alarms to set** (post-cutover, separate ticket):
  - `prod-financeagent-dlq.ApproximateNumberOfMessagesVisible > 0` for 5 min → page
  - `prod-financeagent-results.ApproximateAgeOfOldestMessage > 600s` (10 min) → warn
  - One alarm per `prod-financeagent-tasks-submit-*` for `ApproximateAgeOfOldestMessage > 600s` — catches stuck workers
- **Log groups:** the `@SqsListener` loggers in Praxis write to the existing
  application log stream — no separate log groups needed for queues themselves.

### 3.8 Tagging

Every AWS resource gets these tags **at creation time**:

| Tag           | Value                                    |
| ------------- | ---------------------------------------- |
| `Environment` | `dev` \| `prod`                          |
| `Project`     | `praxis`                                 |
| `Component`   | `financeagent`                           |
| `ManagedBy`   | `manual` (until IaC follow-up)           |

Bill-by-tag and resource-list-by-tag are how we'll do dev cleanup
(`aws resourcegroupstaggingapi get-resources --tag-filters Key=Environment,Values=dev`).

---

## 4. AWS Resources to Provision

### 4.1 SQS queues

**Provisioning order matters:** create the DLQ first, then create working queues
referencing the DLQ ARN in their redrive policy.

**Step 1 — Create DLQ:**

```bash
aws sqs create-queue --queue-name prod-financeagent-dlq --region us-east-1 \
  --tags Environment=prod,Project=praxis,Component=financeagent,ManagedBy=manual \
  --attributes '{"MessageRetentionPeriod":"1209600"}'   # 14 days, max
```

Note the `QueueArn` from the response (or fetch with `aws sqs get-queue-attributes
--queue-url <url> --attribute-names QueueArn`).

**Step 2 — Create each working queue with redrive policy:**

```bash
DLQ_ARN="arn:aws:sqs:us-east-1:409159414704:prod-financeagent-dlq"

for queue in \
  prod-financeagent-tasks-capture-mock-payroll \
  prod-financeagent-tasks-capture-autoplanilla \
  prod-financeagent-tasks-submit-ccss-sicere \
  prod-financeagent-tasks-submit-ins-rt-virtual \
  prod-financeagent-tasks-submit-hacienda-ovi \
  prod-financeagent-tasks-submit-mock-payroll \
  prod-financeagent-results
do
  aws sqs create-queue --queue-name "$queue" --region us-east-1 \
    --tags Environment=prod,Project=praxis,Component=financeagent,ManagedBy=manual \
    --attributes "{\"VisibilityTimeout\":\"900\",\"ReceiveMessageWaitTimeSeconds\":\"20\",\"MessageRetentionPeriod\":\"345600\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}"
done
```

| Attribute                       | Value             | Why                                                                                                                          |
| ------------------------------- | ----------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `VisibilityTimeout`             | 900 (15 min)      | Single-attempt budget. Must be < BPMN Receive Task boundary timer (currently 10 min — see §11). Calibration pending §6.6 Q3. |
| `ReceiveMessageWaitTimeSeconds` | 20                | SQS-level long polling. Reduces empty-receive churn on consumers.                                                            |
| `MessageRetentionPeriod`        | 345600 (4 days)   | Working queues — short retention, anything not consumed in 4 days is stale.                                                  |
| `MessageRetentionPeriod` (DLQ)  | 1209600 (14 days) | DLQ — keep poison messages long enough to investigate. Max allowed by SQS.                                                   |
| `maxReceiveCount`               | 5                 | Retry budget before going to DLQ.                                                                                            |

**Step 3 — Repeat for dev** (swap `prod-` → `dev-` everywhere). Eight more queues.

### 4.2 KMS keys & aliases

**Two keys total:** one prod, one dev, both for NeoProc (firmId=1).

```bash
# Prod NeoProc key
aws kms create-key --region us-east-1 \
  --description "Payroll envelope key — firm 1 (prod)" \
  --key-usage ENCRYPT_DECRYPT --key-spec SYMMETRIC_DEFAULT \
  --tags TagKey=Environment,TagValue=prod TagKey=Project,TagValue=praxis TagKey=firmId,TagValue=1
# Capture KeyId from response, then:
aws kms create-alias --region us-east-1 \
  --alias-name alias/prod-payroll-firm-1 --target-key-id <KeyId>
aws kms enable-key-rotation --region us-east-1 --key-id <KeyId>

# Dev NeoProc key — repeat with --description "Payroll envelope key — firm 1 (dev)"
# alias/dev-payroll-firm-1, Environment=dev tag.
```

This is the **manual backfill** for NeoProc (the `KmsKeyProvisioningListener`
doesn't fire because NeoProc was created pre-Phase-11B).

For **future** firms, the listener provisions automatically — see §5.2 for the
config change to make the alias pattern env-aware.

### 4.3 Secrets Manager (mock data for dev, real for prod when ready)

**Dev — create mock secrets** so local cycles can resolve credentials:

```bash
aws secretsmanager create-secret --region us-east-1 \
  --name dev/financeagent/shared/portals/mock-payroll \
  --secret-string '{"username":"mock","password":"mock","mfaMethod":"none"}' \
  --tags Key=Environment,Value=dev Key=Project,Value=praxis

aws secretsmanager create-secret --region us-east-1 \
  --name dev/financeagent/firms/1/portals/ccss-sicere/3-101-680139 \
  --secret-string '{"username":"mock","password":"mock"}' \
  --tags Key=Environment,Value=dev Key=Project,Value=praxis
```

**Prod — defer until cycle-1 prep.** Real CCSS/INS/Hacienda credentials get
created on the day of the first real cycle, not earlier.

### 4.4 IAM policies

JSON documents to be added to [infra/iam/](../infra/iam/) as part of this work:

- `PraxisSqsFinanceagent.json` (NEW)
- `PraxisDeveloperLocal.json` (NEW)
- Update `PraxisKmsEnvelope.json` (change `kms:RequestAlias` constraint from
  `alias/payroll-firm-*` → `alias/prod-payroll-firm-*`)
- Update `PraxisSecretsManagerFinanceagent.json` (change `Resource` from
  `arn:...:secret:financeagent/*` → `arn:...:secret:prod/financeagent/*`)

Skeleton for `PraxisSqsFinanceagent.json`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowFinanceagentQueueOps",
      "Effect": "Allow",
      "Action": [
        "sqs:SendMessage",
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes",
        "sqs:GetQueueUrl",
        "sqs:ChangeMessageVisibility"
      ],
      "Resource": "arn:aws:sqs:*:*:prod-financeagent-*"
    }
  ]
}
```

Skeleton for `PraxisDeveloperLocal.json`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowDevSqs",
      "Effect": "Allow",
      "Action": "sqs:*",
      "Resource": "arn:aws:sqs:*:*:dev-financeagent-*"
    },
    {
      "Sid": "AllowDevKms",
      "Effect": "Allow",
      "Action": ["kms:GenerateDataKey", "kms:Decrypt", "kms:Encrypt", "kms:DescribeKey"],
      "Resource": "arn:aws:kms:*:*:key/*",
      "Condition": {
        "StringLike": { "kms:RequestAlias": "alias/dev-payroll-firm-*" }
      }
    },
    {
      "Sid": "AllowDevKmsProvisioning",
      "Effect": "Allow",
      "Action": ["kms:CreateKey", "kms:CreateAlias", "kms:ListAliases", "kms:EnableKeyRotation", "kms:TagResource"],
      "Resource": "*"
    },
    {
      "Sid": "AllowDevSecrets",
      "Effect": "Allow",
      "Action": "secretsmanager:*",
      "Resource": "arn:aws:secretsmanager:*:*:secret:dev/financeagent/*"
    }
  ]
}
```

---

## 5. Praxis Code Changes

### 5.1 Maven dependencies

**Add** to `pom.xml`:

```xml
<dependency>
  <groupId>io.awspring.cloud</groupId>
  <artifactId>spring-cloud-aws-starter-sqs</artifactId>
  <version>3.2.1</version>   <!-- pin; check latest at the time of work -->
</dependency>
```

**Remove:**

```xml
<!-- Spring AMQP — no longer used after Phase 11D switch -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

(Verify with `mvn dependency:tree | grep amqp` after removal — should be empty.)

### 5.2 Configuration

**`application.yml`** — replace the existing `praxis.mq.*` block:

```yaml
praxis:
  kms:
    enabled: false
    region: ${AWS_REGION:us-east-1}
    alias-pattern: 'alias/dev-payroll-firm-%d'   # dev default; aws profile overrides
  sqs:
    enabled: ${PRAXIS_SQS_ENABLED:false}        # gate publisher + listener bean creation
    queue-prefix: dev                            # 'dev' or 'prod' — drives queue lookups
  payroll:
    encryption:
      enabled: false
```

**`application-aws.yml`** — replace `praxis.mq.*` and `praxis.kms.alias-pattern`:

```yaml
praxis:
  kms:
    enabled: true
    region: ${AWS_REGION:us-east-1}
    alias-pattern: 'alias/prod-payroll-firm-%d'  # CHANGED from alias/payroll-firm-%d
  sqs:
    enabled: ${PRAXIS_SQS_ENABLED:true}          # default ON in aws profile
    queue-prefix: prod
  payroll:
    encryption:
      enabled: false
```

**Spring Cloud AWS config** — relies on standard AWS SDK provider chain for
credentials (instance profile in EB, `~/.aws/credentials` locally), so no
additional config keys needed unless overriding the endpoint for LocalStack:

```yaml
# application-localstack.yml (NEW profile, activated via SPRING_PROFILES_ACTIVE for local dev)
spring.cloud.aws:
  region.static: us-east-1
  credentials:
    access-key: test
    secret-key: test
  endpoint: http://localhost:4566
  sqs.endpoint: http://localhost:4566
  kms.endpoint: http://localhost:4566
  secretsmanager.endpoint: http://localhost:4566
```

### 5.3 Code replacements

**Delete entirely:**

- `src/main/java/com/neoproc/praxis/config/AgentWorkerRabbitMQConfiguration.java`

**Add:**

- `src/main/java/com/neoproc/praxis/config/AgentWorkerSqsConfiguration.java` — exposes
  queue-name constants (e.g. `tasksCaptureQueueName(portalId)` returning
  `<prefix>-financeagent-tasks-capture-<portalId>`). No bean wiring beyond what
  Spring Cloud AWS auto-configures.

**Modify:**

- `src/main/java/com/neoproc/praxis/service/bpm/PayrollServiceTaskDelegate.java`:
  - Inject `io.awspring.cloud.sqs.operations.SqsTemplate` instead of `RabbitTemplate`.
  - Replace `rabbitTemplate.convertAndSend(exchange, routingKey, payload, props)` with:
    ```java
    sqsTemplate.send(to -> to
        .queue(queueName)                                    // resolved from portalId + prefix
        .payload(payloadJson)
        .header("schemaName", schemaName)
        .header("envelopeId", envelopeId.toString())
        .header("businessKey", businessKey)
        .header("type", schemaName));
    ```
  - Schema validation, encryption gating, audit emission — **unchanged**.

- `src/main/java/com/neoproc/praxis/service/bpm/PayrollResultListener.java`:
  - Replace `@RabbitListener(queues = "financeagent.results")` with
    `@SqsListener("${praxis.sqs.queue-prefix}-financeagent-results")`.
  - Listener body: take `Message<String>` (Spring Messaging type), extract headers
    via `MessageHeaders`, validate body with `SchemaValidator`, dispatch.
  - All the schema-validation HITL escalation logic — **unchanged**.

- `src/main/java/com/neoproc/praxis/service/bpm/KmsConfiguration.java` (or
  wherever the alias pattern is read): no code change, but the `application.yml`
  default flips from `alias/payroll-firm-%d` to `alias/dev-payroll-firm-%d`.

### 5.4 Tests

**Convert:**

- `PayrollServiceTaskDelegateTest` — replace `RabbitTemplate` mock with `SqsTemplate`
  mock. Assert `send()` called with the right queue name + headers. ~30 min change.
- `PayrollResultListenerTest` — call the listener method directly with synthesized
  `Message<String>`. No transport involved. ~15 min change.

**Add:**

- `PayrollSqsRoundTripIT` — `@Testcontainers` with `LocalStackContainer.withServices(SQS, KMS, SECRETSMANAGER)`.
  Spring Cloud AWS gets the LocalStack endpoint via test properties. Send a real
  envelope → assert it lands on the right queue → assert listener processes it →
  assert BPMN Receive Task signaled.

**Delete:**

- `PayrollResultListenerTest`'s RabbitMQ-specific helpers and any
  `RabbitListenerTestHarness` usage.
- The currently-broken `surefire <exclude>**/*IT*</exclude>` pattern that catches
  `HITLFallbackHandlerTest` (per memory, 2026-04-24) is unrelated to this work
  but worth fixing in the same PR so we don't regress.

### 5.5 Docker Compose / local dev

**`src/main/docker/services.yml`** — drop RabbitMQ + Vault, add LocalStack:

```yaml
# DROP:
#   rabbitmq:
#     image: rabbitmq:management
#     ports: [5672, 15672]
#   vault:
#     image: hashicorp/vault
#     ...

# ADD:
localstack:
  image: localstack/localstack:3.0
  ports:
    - "4566:4566"
  environment:
    SERVICES: sqs,kms,secretsmanager
    DEBUG: 0
    PERSISTENCE: 1
  volumes:
    - localstack-data:/var/lib/localstack

volumes:
  localstack-data:
```

A small init script (`src/main/docker/localstack-init.sh`) creates the dev queues
+ KMS alias + secrets on container start so devs don't have to run AWS CLI
commands manually.

### 5.6 Documentation updates

- **`docs/LocalDevPayrollE2E.md`** — substantial rewrite. RabbitMQ → LocalStack
  SQS. Manual envelope replay step changes from RabbitMQ Management UI to
  `aws --endpoint-url=http://localhost:4566 sqs send-message`.
- **`docs/PraxisIntegrationHandoff.md`** — §C.1 and §C.2 (broker topology +
  message envelope AMQP properties) are rewritten using the addendum from §6.
- **`docs/Phase8SliceProvisioning.md`** — §3 (Amazon MQ section) replaced with
  a reference to this plan. §1 (IAM) extended with the new policies from §4.4.
- **`CLAUDE.md`** — update tech-stack section: `RabbitMQ` → `AWS SQS`. Update
  the architecture module list.
- **`MEMORY.md`** — Phase 11D entries get a "superseded by SQS" note pointing here.

---

## 6. Agent-Worker Contract Addendum [SEND TO AGENT-WORKER TEAM]

This is the section to lift verbatim into a Slack message / email / PR
description for the agent-worker team. Self-contained.

> ### Subject: SQS migration proposal for Phase 11D transport
>
> We're proposing to switch the Phase 11D transport between Praxis and the
> agent-worker from Amazon MQ (RabbitMQ) to AWS SQS. Driver is cost (~$80/mo →
> ~$1/mo) and architectural consistency with our AWS-native stack. The wire
> format (envelope schemas, `contract-api` JAR, encryption, JSON-Schema validation)
> is unchanged — only the transport adapter on both sides changes.
>
> Estimated work on your side: 3–5 engineer-days.
>
> ### 6.1 Queue naming (SQS)
>
> **The pattern is the contract; specific portal queues below are the current
> portals.** Adding a new portal in the future means adding queues + a worker
> container under the existing pattern, with no contract change.
>
> Pattern: `<env>-financeagent-<role>[-<portal-id>]`
>
> | Component   | Allowed values                                                                     |
> | ----------- | ---------------------------------------------------------------------------------- |
> | `<env>`     | `dev` \| `prod` (always explicit, no implicit prod)                                |
> | `<role>`    | `tasks-capture` \| `tasks-submit` \| `results` \| `dlq`                            |
> | `<portal-id>` | Required for `tasks-capture` and `tasks-submit` only. Currently: `mock-payroll`, `autoplanilla`, `ccss-sicere`, `ins-rt-virtual`, `hacienda-ovi` |
>
> Each AMQP exchange + routing-key combo from the existing
> [PraxisIntegrationHandoff.md §C.1](PraxisIntegrationHandoff.md#c1-broker--topology)
> becomes one SQS Standard queue. Current concrete prod queue list (8 queues):
>
> ```
> prod-financeagent-tasks-capture-mock-payroll
> prod-financeagent-tasks-capture-autoplanilla
> prod-financeagent-tasks-submit-ccss-sicere
> prod-financeagent-tasks-submit-ins-rt-virtual
> prod-financeagent-tasks-submit-hacienda-ovi
> prod-financeagent-tasks-submit-mock-payroll
> prod-financeagent-results
> prod-financeagent-dlq
> ```
>
> Dev queues: same with `dev-` prefix. Region `us-east-1`. Single AWS account
> (NeoProc's, pending §6.6 Q2 confirmation).
>
> #### Worker subscription rule
>
> **One worker container per `PORTAL_ID`.** That container subscribes to whichever
> of `<env>-financeagent-tasks-capture-<portalId>` and
> `<env>-financeagent-tasks-submit-<portalId>` exist for its portal — for portals
> with both flows (e.g. `mock-payroll`, `autoplanilla`), the same worker container
> subscribes to **two** `tasks-*` queues; for submit-only portals (e.g.
> `ccss-sicere`, `ins-rt-virtual`, `hacienda-ovi`), it subscribes to one.
>
> All workers, regardless of portal, publish to the single shared
> `<env>-financeagent-results` queue. Praxis is the only consumer of that queue.
>
> #### Concurrency / ordering invariant
>
> Multi-instance worker pools per `PORTAL_ID` are **ordering-safe under current
> usage** because the BPMN orchestration enforces ≤ 1 in-flight envelope per
> (cycle, portal) pair. A future cycle pattern that batches multiple envelopes
> for one portal in parallel within a single cycle would invalidate this and
> require migration to FIFO queues + `MessageGroupId` keyed on `(cycle, portal)`.
> Until then, Standard queues with N workers per portal are correct.
>
> ### 6.2 Message attribute shape (replaces AMQP headers)
>
> SQS uses **MessageAttributes** (typed, separate from body) instead of AMQP
> headers. Mapping (all attributes are SQS `String` data type):
>
> | AMQP header today        | SQS MessageAttribute      | Allowed values                                     |
> | ------------------------ | ------------------------- | -------------------------------------------------- |
> | `correlation-id`         | `businessKey`             | Same as `envelope.businessKey` in the body         |
> | `reply-to`               | (drop)                    | — single shared results queue makes this implicit  |
> | `type`                   | `schemaName`              | One of the 4 canonical contract-api schemas (below) |
> | `content-type`           | (drop)                    | — always `application/json`                        |
> | `message-id`             | `envelopeId`              | Same as `envelope.envelopeId` in the body (UUID)   |
>
> #### Canonical schema names (`schemaName` attribute)
>
> The `schemaName` attribute carries one of the four canonical contract-api
> wire-format schemas. **No other values are valid.** These are the JSON
> Schema names registered in the `contract-api` JAR; they're separate from
> Praxis-internal BPMN message-ref names (e.g. `payroll-submit-result-mock.v1`)
> which the worker never sees.
>
> ```
> payroll-capture-request.v1
> payroll-capture-result.v1
> payroll-submit-request.v1
> payroll-submit-result.v1
> ```
>
> #### Canonical source rule (body wins)
>
> The envelope body and the MessageAttributes **both** carry `envelopeId` and
> `businessKey`. They must agree. **The body is canonical** — if they
> disagree, treat as a corrupt envelope and route to the DLQ. Practical
> implications:
>
> - JSON-Schema validation is the source of truth: it validates the body, not
>   the attributes. A malformed attribute does not pass validation.
> - The agent-worker's `InMemoryIdempotencyStore` (per memory: Caffeine,
>   7-day TTL) keys on `envelope.envelopeId` from the body.
> - Praxis's BPMN correlation keys on `envelope.businessKey` from the body
>   (looked up via `runtimeService.createProcessInstanceQuery().processInstanceBusinessKey()`).
> - Attributes exist only as **routing convenience** for SQS-level filtering,
>   CloudWatch dimensions, and human-readable debugging in the AWS console.
>   Treat them as a redundant signal, not a source of truth.
>
> #### `businessKey` passthrough
>
> The result envelope's `envelope.businessKey` (body) **and** `businessKey`
> attribute **must equal** the request's. A mismatch on either breaks Praxis's
> BPMN correlation and the result is dropped (HITL escalation created via the
> existing schema-mismatch path).
>
> Praxis emits both on every send. Worker echoes both on the response message.
> When in doubt, copy them verbatim from the inbound request to the outbound
> result.
>
> #### Reading attributes in Spring Cloud AWS
>
> Spring Cloud AWS surfaces `MessageAttributes` as Spring `MessageHeaders`,
> but reserves several names for its own use (`Sqs_*` prefix, `id`, `timestamp`,
> `contentType`). Our four attribute names (`businessKey`, `schemaName`,
> `envelopeId`) are safe — none collide with reserved names. Read them in
> listener methods via:
>
> ```java
> @SqsListener("${queue-name}")
> public void onResult(@Payload String body,
>                      @Header("schemaName") String schemaName,
>                      @Header("envelopeId") String envelopeId,
>                      @Header("businessKey") String businessKey) { ... }
> ```
>
> Producer side sets them as `String`-typed `MessageAttributes` (not as Spring
> message headers, which would land in a different SQS slot).
>
> #### Schema versioning policy
>
> The four `*.v1` schema names above are the wire-format contract today. A
> future `*.v2` introduction is a **coordinated rollout**, not a unilateral
> bump:
>
> 1. Both sides ship support for the new version while still accepting the
>    old version (one cycle of overlap minimum).
> 2. Senders MUST emit the version corresponding to the schema their producer
>    code was validated against — no "best-effort" downgrade.
> 3. Receivers MUST reject envelopes carrying an unknown `schemaName` to the
>    DLQ (do not best-effort-parse).
> 4. After the overlap cycle confirms both sides are emitting `v2` cleanly,
>    `v1` is removed in a subsequent coordinated change.
>
> This protocol is asymmetric — the side introducing the new schema
> announces it; the other side ships acceptance support **before** the first
> `v2` envelope is published.
>
> ### 6.3 Queue attributes, ack semantics, redelivery
>
> #### Redrive policy
>
> Each working queue has redrive policy:
>
> ```json
> {
>   "deadLetterTargetArn": "arn:aws:sqs:us-east-1:<acct>:prod-financeagent-dlq",
>   "maxReceiveCount": 5
> }
> ```
>
> #### Visibility timeout — 900s (15 min) baseline, calibration pending
>
> Set `VisibilityTimeout=900` on every working queue. **Calibration of this
> value is one of the open questions** — see §6.6 Q3. Bumped from an initial
> 600s (10 min) draft to give breathing room over the BPMN Receive Task
> boundary timer (currently 10 min; may bump to 30 min in §11).
>
> Required ordering: **visibility timeout < BPMN Receive Task timer**. That
> way a single attempt has time to succeed under the broker's patience window;
> if it fails, redelivery happens before BPMN gives up. Reverse ordering would
> mean BPMN escalates while the worker is still mid-attempt — recoverable
> (the late-arriving result lands as an HITL escalation per the existing
> schema-mismatch path) but ugly.
>
> If a single portal interaction routinely runs > 15 min, the worker can call
> `ChangeMessageVisibility` mid-task to extend the lock — Spring Cloud AWS
> exposes this via the `Visibility` interface in `@SqsListener` argument
> resolution. Document this if any portal needs it; otherwise leave the
> 15-min visibility timeout as the cap.
>
> #### Long polling
>
> Set `ReceiveMessageWaitTimeSeconds=20` on every working queue and on the
> results queue. Reduces empty-receive churn (cost and CPU on workers + Praxis)
> from ~3,600/hr down to ~180/hr per consumer. SQS caps at 20 seconds; that's
> the value to set.
>
> #### Ack semantics — listener throws on result-publish failure
>
> Spring Cloud AWS `@SqsListener` defaults to `ON_SUCCESS` acknowledgement —
> the message is auto-deleted when the listener method returns normally. This
> creates a silent-failure mode if the listener does portal work successfully
> and then fails to publish the result (network blip, transient
> `SqsTemplate.send` failure on the results queue): the inbound message gets
> deleted, the result never lands on Praxis's side, the BPMN Receive Task
> hangs until its boundary timer fires.
>
> **Required behavior:** listener throws if the result publish fails. SQS
> redelivers after the visibility timeout, application-layer idempotency
> (worker-side `InMemoryIdempotencyStore` keyed on `envelopeId`) catches the
> duplicate attempt, the worker **re-publishes the cached result body** —
> not just a "seen" marker. See Q6 in §6.6 for confirmation that the store
> caches the full result body for the configured TTL, which is a precondition
> of this recovery path. Without full-body caching, redelivery either
> short-circuits (no result lands → BPMN times out) or re-executes the
> portal interaction (duplicate side effects, violates the cédula PK
> matcher contract for CCSS).
>
> Same throw-on-publish-failure rule applies on Praxis's side for the result
> listener: if BPMN message correlation fails for a transient reason, throw
> — let SQS redeliver — let the dispatch retry.
>
> #### Praxis-side dedup is implicit (no app cache)
>
> Praxis does **not** maintain an application-layer cache on the result-ingest
> path. Dedup is implicit in BPMN correlation: a duplicate result envelope
> for a process instance whose Receive Task has already advanced is absorbed
> silently by Flowable's message-correlation layer (the duplicate signal lands
> on a non-active Receive Task and the engine no-ops). This is intentional —
> the worker-side cache is the safety net for the worker's at-least-once
> publish loop; Praxis's safety net is the BPMN engine's natural idempotency.
>
> #### Ack-on-business-failure
>
> Ack-on-business-failure (e.g. portal returned `MISMATCH` or `FAILED`) is
> **fine**. Those are valid result states encoded in the body and should NOT
> trigger redelivery — the message is delivered, the business outcome is what
> it is, the human review path picks it up.
>
> ### 6.4 Encryption (unchanged)
>
> Body-level envelope encryption is untouched by this migration. The
> `aws-kms-envelope-v1` (wire prefix `vault:v1:`) scheme still applies to the
> envelope `result` / `request` field. SQS message body carries the JSON
> envelope as-is, encrypted or not depending on `Encryption` block.
>
> ### 6.5 IAM requirements for your Fargate task role
>
> Three new policies (we'll provide the JSON; you attach to the Fargate task
> role):
>
> - `AgentWorkerSqsFinanceagent` — `Receive/Delete/ChangeMessageVisibility/GetQueueAttributes/GetQueueUrl`
>   on `arn:aws:sqs:*:*:prod-financeagent-tasks-*` and `SendMessage` on
>   `arn:aws:sqs:*:*:prod-financeagent-results`.
> - `AgentWorkerSecretsFinanceagent` — `secretsmanager:GetSecretValue` on
>   `prod/financeagent/firms/*/portals/*` and `prod/financeagent/shared/portals/*`.
> - `AgentWorkerKmsDecrypt` — `kms:Decrypt` on `alias/prod-payroll-firm-*`.
>   Creation is **explicitly conditional on Q8's encryption-ETA decision**
>   (see §6.6 Q8):
>   - If Q8 lands as **"flip encryption with the SQS migration"**: this
>     policy is created and attached as part of the cutover work, in lockstep
>     with `praxis.payroll.encryption.enabled=true`.
>   - If Q8 lands as **"defer encryption"**: this policy creation is recorded
>     as a **named precondition** in `docs/EnhancementsBacklog.md` against
>     the future "enable envelope encryption" item, **not** silently parked.
>     The encryption-enable PR cannot merge until the policy is attached and
>     verified, otherwise the first encrypted envelope hits a KMS
>     `AccessDenied` an hour into cutover.
>
> **ARN scoping note:** the wildcards above (`*:*:prod-financeagent-*`) assume
> **same-account** deployment — both Praxis and the agent-worker run in the
> NeoProc AWS account. If §6.6 Q2 lands as cross-account, the second `*`
> (account ID) becomes the explicit NeoProc account ID, and the queue's
> resource policy needs a `Principal` block granting access to the worker's
> role ARN (or temporary credentials via `sts:AssumeRole`).
>
> ### 6.6 Open questions for you
>
> 1. **Confirm sign-off in principle.** Reading your feedback as conditional
>    yes-with-refinements — am I right? If you'd rather not switch, halt the
>    plan now rather than ship parallel transports.
>
>    **A (agent-worker, 2026-05-06):** ✅ Yes, switching. Round-3 alignment
>    confirmed.
>
> 2. **Same-account vs cross-account.** Do you run in our AWS account or
>    yours? If yours, we need to figure out cross-account SQS access
>    (KMS-encrypted queues + queue resource policy granting your role ARN, or
>    temporary credentials via `sts:AssumeRole`). This drives the IAM design
>    in §6.5.
>
>    **A (agent-worker, 2026-05-06):** ✅ Same-account. The worker is
>    currently greenfield-AWS — no AWS SDK config in the codebase today, no
>    cross-account indicators. Will deploy into the NeoProc account; the
>    wildcard ARNs in §6.5 stand as written.
>
> 3. **Visibility timeout calibration.** What's the P95 (or worst observed)
>    end-to-end portal-interaction time from the recent live runs (CCSS
>    Sicere E2E 2026-04-29, INS RT-Virtual stabilization)? The plan currently
>    proposes 900s (15 min); if any portal routinely runs longer, we either
>    bump to 1200s/1800s or document the `ChangeMessageVisibility` heartbeat
>    pattern.
>
>    **A (agent-worker, 2026-05-06):** Data is sparse — only one
>    production-instrumented run in the repo (INS RT-V 2026-05-05). CCSS
>    Sicere 2026-04-29 timings live only in Praxis prod logs, not checked in.
>    Mock-payroll has no production timing runs.
>
>    | Portal           | P50 | P95 | Worst observed         | Sample size |
>    | ---------------- | --- | --- | ---------------------- | ----------- |
>    | INS RT-Virtual   | —   | —   | 62s                    | n=1         |
>    | CCSS Sicere      | —   | —   | not in repo            | n=0         |
>    | Mock-payroll     | —   | —   | no prod runs           | n=0         |
>
>    INS run breakdown: ~7s auth/nav, ~30s row scrape (15 portal rows),
>    ~22s row matching + salary application overlapping with scrape, ~3s
>    resumen verification, <1s envelope serialization.
>
>    **Decision:** keep 900s. The single observed datapoint is ~62s —
>    14-min headroom. The decision is somewhat under-grounded (n=1) but:
>    (a) dominant cost is per-row portal interaction, scales linearly with
>    employee count; (b) at 5× the current INS row count we're under 6 min;
>    (c) `ChangeMessageVisibility` heartbeat is the documented escape hatch.
>
>    **Watch:** after dev-parity runs (§7.2), re-check actual end-to-end
>    timings against the 900s baseline; if any P95 lands above 600s, bump
>    visibility before prod cutover. Tracked in §10 cutover section.
>
> 4. **`spring-cloud-aws-starter-sqs` fit.** Does it work for your worker's
>    Spring/Spring-less topology, or do you need a different SQS client (raw
>    AWS SDK v2)?
>
>    **A (agent-worker, 2026-05-06):** ✅ Clean fit. Worker is Spring Boot,
>    currently uses `spring-boot-starter-amqp` for the `@RabbitListener`
>    topology. `spring-cloud-aws-starter-sqs` swaps in alongside / replaces
>    it without restructuring the listener layer — annotation-level swap
>    plus the publisher swap. No raw AWS SDK v2 needed.
>
> 5. **Naming convention sign-off.** Anything you'd change about the queue /
>    IAM / secret-path names? Once locked, this is contract.
>
>    **A (agent-worker, 2026-05-06):** ✅ Sign-off, no changes. Current AMQP
>    topology uses dot-separated names; SQS forbids dots so hyphens are
>    forced. The proposed `<env>-financeagent-<role>[-<portal-id>]` pattern
>    is a clean translation; env prefix is a net improvement. **Locked.**
> 6. **Idempotency store — restart survival AND result-body caching.** Two
>    related axes on the worker's `InMemoryIdempotencyStore`:
>
>    **6a. Restart survival.** Per memory the store is Caffeine-backed
>    (process-local, 7-day TTL). Are you (a) confirming current behavior is
>    sufficient because Praxis's at-least-once + body-canonical envelopeId
>    catches dupes end-to-end, or (b) flagging that you'd want to migrate to
>    a persistent backing store (Redis/DynamoDB) before prod? If (b), that's
>    substantial extra scope on your side — surface it now.
>
>    **A (agent-worker, 2026-05-06):** Process-local, does NOT survive
>    restart. Caffeine `Cache<String, Boolean>`, 7-day TTL, 50,000-entry
>    cap. Class javadoc explicitly notes: "a restart that triggers
>    redelivery of an already-processed message will result in a duplicate
>    portal action." For payroll cycle volumes (~10 envelopes/cycle, weekly
>    cadence) the risk is small but real. **Posture (a):** Caffeine is
>    sufficient for v1; persistent backing store (Redis/DynamoDB) tracked
>    as `EnhancementsBacklog.md` follow-up if cycle frequency or volume
>    increases. No pre-prod scope add.
>
>    **6b. Full result body vs marker-only.** The throw-on-publish-failure
>    recovery path in §6.3 requires the store to cache the **full result body**
>    for the configured TTL — not just an "envelopeId seen" marker. On
>    redelivery, the worker re-publishes the cached body without re-running
>    the portal interaction. A marker-only store breaks this: the worker
>    either skips (no result ever lands → BPMN times out at 30 min) or
>    re-executes the portal interaction (duplicate side effects, violates
>    the cédula primary-key matcher contract for CCSS). **Confirm whether
>    the current store caches full result bodies.** If marker-only today,
>    upgrading to full-body caching is a small but real implementation item
>    that needs to land before cutover.
>
>    **A (agent-worker, 2026-05-06):** ⚠️ **Marker only today.** Store puts
>    `Boolean.TRUE` against `envelopeId`; no result body cached. Current
>    `PayrollTaskListener` behavior on duplicate hit: logs warning, returns
>    a synthetic `DUPLICATE_ENVELOPE` failed result, skips portal execution.
>    This means the redelivery-on-publish-failure path **today** would surface
>    as: portal work succeeds → publish fails → throw → SQS redelivers →
>    duplicate hit → emits synthetic `DUPLICATE_ENVELOPE` failure → Praxis
>    correlates a failure result against the BPMN process → **HITL
>    escalation for an outcome that actually succeeded.**
>
>    **Resolution:** Worker upgrades store from `Cache<String, Boolean>` →
>    `Cache<String, ResultEnvelope>`. On duplicate hit, re-publish the cached
>    envelope instead of synthetic failure. Memory cost: bump cap from
>    50K → 10K entries (worst-case 50K × ~10KB = 500MB; 10K × ~10KB =
>    100MB). Plus belt-and-suspenders: bounded retry-with-backoff (3
>    attempts, exponential) around `SqsTemplate.send` to results queue
>    before letting the listener throw. **Tracked in §10 as a precondition
>    of the round-2 §6.3 recovery contract.** Round-2 §6.3 wording stays
>    correct as-is once this upgrade ships.
> 7. **Largest envelope size you've observed.** Recent runs (CCSS 9, INS-RTV
>    13/14 employees) are well below SQS's 256 KB body cap, but a Hacienda
>    corporate payroll for hundreds of employees could blow it — and
>    encryption-on inflates body size by ~33% (base64). See §6.7 for the
>    decision tree.
>
>    **A (agent-worker, 2026-05-06):**
>    - **Largest observed today:** ~8 KB cleartext (CCSS Sicere
>      9-employee submit envelope, ~900 bytes per `EmployeeRow`).
>    - **Projected 12-month ceiling:** ~150 KB cleartext. CR
>      corporate-payroll sizing for NeoProc's near-term roadmap (Hacienda
>      corporate clients) tops out at ~150–200 employees per envelope.
>    - After +33% encryption inflation: largest today ≈ 11 KB; ceiling ≈
>      200 KB.
>
>    **Decision:** ✅ **Option A (raw SQS).** Comfortable margin under the
>    256 KB cap even at the projected encrypted ceiling. No S3 bucket /
>    Extended Client dependency for v1.
>
>    **Watch threshold:** if any single client envelope exceeds **180 KB
>    cleartext**, revisit Option B before the next cycle. Tracked in §10
>    cutover section.
>
> 8. **Encryption ETA.** Orthogonal but worth syncing. Praxis side has
>    `KmsEnvelopeClient` ready (encrypt path; the decrypt path stays unused
>    per [CONTRACT.md §A.3](../contract-api/CONTRACT.md) asymmetry note).
>    Worker side needs `AwsKmsCipher`. Want to flip encryption on with the
>    SQS migration or keep it deferred?
>
>    **A (agent-worker, 2026-05-06):** ✅ **Defer.** Reasons:
>    1. **Risk surface.** SQS migration is already a transport swap on both
>       sides + a BPMN timer change + IAM/KMS provisioning. Adding
>       encryption-flip in the same cycle doubles the cycle-1 incident
>       surface.
>    2. **Known schema-side bug.** Per memory the contract-api
>       `ciphertextField` schema is dormant-but-broken — fires when
>       encryption flips on. Per-schema `Encryption` `$def` fix is a
>       separate PR that should land first.
>    3. **Past incident** (Praxis OI-001, wire-cipher mismatch) shows this
>       area has had breakage; combining with a transport migration
>       multiplies diagnosis cost.
>    4. **Worker `AwsKmsCipher` is genuinely new code** — better as a
>       focused PR with focused tests, not bundled.
>    5. **Sizing fine without it.** Q7 ceiling is 200 KB encrypted,
>       comfortable margin; encryption isn't size-pushing us toward Option B.
>    6. **Transport security baseline acceptable.** SQS is private VPC
>       traffic, TLS in transit, AWS-managed-key encryption at rest.
>       Body-level KMS envelope encryption is a defense-in-depth upgrade,
>       not a baseline gap.
>
>    **Sequencing:** SQS migration cycle (this plan) → `ciphertextField`
>    schema fix → `AwsKmsCipher` + flip. **`AgentWorkerKmsDecrypt` policy
>    is NOT created during the SQS cycle**; tracked in
>    `EnhancementsBacklog.md` as the named precondition of the encryption
>    flip per round-2 §6.5 / §10's conditional checklist line.
>
> ### 6.7 Payload size & SQS Extended Client decision
>
> SQS body limit is **256 KB** (262,144 bytes). Two factors push toward this:
>
> 1. **Employee count.** Each `EmployeeRow` is roughly ~600–1200 bytes JSON
>    depending on rosterDiff fields. 200 employees ≈ 200 KB, marginal. 500
>    employees ≈ 500 KB, blown.
> 2. **Encryption inflation.** Base64-wrapping the AES-GCM ciphertext adds
>    ~33%. So a 200 KB cleartext body becomes ~270 KB after encryption — past
>    the limit even when the cleartext fits.
>
> Three options, ranked by cost-to-implement:
>
> | Option | When it makes sense | Implementation cost |
> | ------ | ------------------- | ------------------- |
> | **A. Stay with raw SQS** | Confident max envelope ≤ 150 KB cleartext (≤ 200 KB encrypted) for all foreseeable cycles | Zero — current plan |
> | **B. SQS Extended Client (S3-backed)** | Any current portal already > 100 KB, OR roadmap includes envelopes > 150 KB | Both sides depend on `amazon-sqs-java-extended-client-lib`; bodies that exceed a configured threshold (e.g. 64 KB) get persisted to S3 with the SQS message carrying only an S3 pointer; consumer transparently fetches. ~1–2 days extra on each side, plus an S3 bucket + IAM policy. **Contract change** — this would alter the addendum. |
> | **C. Body chunking in the contract** | Same situation as B but you don't want an S3 dependency | Custom logic on both sides to split/reassemble large envelopes. Higher cost than B for less robustness. Not recommended. |
>
> **Recommendation:** default to A unless §6.6 Q7 surfaces a current or
> near-term envelope > 150 KB. If we're at risk, jump to B before any code
> ships — it's a contract-level decision, not a tuning knob to flip later.

---

## 7. Test Strategy

### 7.1 Tier 1 — LocalStack (daily inner loop)

- **What:** Docker Compose with Postgres + LocalStack, Praxis runs locally with
  `SPRING_PROFILES_ACTIVE=dev,localstack`.
- **Used for:** logic iteration, BPMN debugging, listener wiring, manual
  envelope replay.
- **Cost:** $0.
- **Catches:** logic bugs, schema bugs, BPMN routing, audit emissions.
- **Misses:** real IAM evaluation, real KMS round-trip, real AWS quotas.

### 7.2 Tier 2 — Real-AWS dev parity (pre-prod gate)

- **What:** Same code on your laptop, but pointed at real `dev-financeagent-*`
  queues + `alias/dev-payroll-firm-1` KMS + `dev/financeagent/*` secrets in
  the NeoProc AWS account. Use AWS profile `[default]` (your personal IAM user
  with `PraxisDeveloperLocal` policy attached).
- **Used for:** final validation before prod cutover. Cross-system test with
  the agent-worker team running their worker against the same dev queues.
- **Cost:** ~$0.10/month for SQS + ~$1/month for KMS keys (delete after if you want).
- **Catches:** IAM policy bugs, KMS encryption symmetry between sides, real
  network behavior, Secrets Manager path scoping.
- **Misses:** EB-specific config behavior (env var resolution under the EB
  Spring profile activation), Liquibase migration apply against real RDS.

### 7.3 CI integration tests

- Use `LocalStackContainer.withServices(SQS, KMS, SECRETSMANAGER)` from
  `org.testcontainers:localstack`. Spring Cloud AWS picks up the endpoint via
  test properties.
- Existing Testcontainers/Postgres pattern carries over directly — same
  `@DynamicPropertySource` approach.

---

## 8. Cutover Plan

### Week 1 — Coordination + design

- [ ] Send §6 addendum to agent-worker team. Get sign-off on §6.6 questions.
- [ ] Agree on cross-account vs same-account model for the worker's IAM.
- [ ] Lock the naming convention in §3.

### Week 2 — Code

- [ ] Praxis: implement §5.1 → §5.5. Tests green.
- [ ] Agent-worker: implement their half. Tests green on their side.
- [ ] Both: code-review each other's changes.

### Week 3 — Dev parity

- [ ] Provision dev AWS resources per §4 (dev-prefixed). ~30 min.
- [ ] Run cross-system mock-payroll cycle: local Praxis + local agent-worker,
      both pointed at real dev SQS. Verify round-trip + audit + KMS encryption.
- [ ] Both teams sign off.

### Week 3 — Prod prep

- [ ] Provision prod AWS resources per §4 (prod-prefixed). ~30 min.
- [ ] Provision NeoProc prod KMS key manually (`alias/prod-payroll-firm-1`).
- [ ] Set EB env vars: `PRAXIS_SQS_ENABLED=true`, no others needed (Spring
      Cloud AWS uses instance profile credentials).
- [ ] Take RDS snapshot.
- [ ] Merge to main, EB deploys.

### Week 3 — Smoke

- [ ] **Friday afternoon** trigger mock-payroll cycle in prod.
- [ ] Verify round-trip end-to-end. KMS provisioning happened on first boot.
      Audit trail looks right. No DLQ messages.
- [ ] Weekend buffer for debugging if needed.

### Week 4 — First real cycle

- [ ] First real CCSS/INS/Hacienda submission, supervised in real-time.
- [ ] Have rollback ready (workflow cancel + manual fallback).

---

## 9. Rollback Plan

If a serious issue surfaces after prod cutover:

1. **Workflow-level rollback (preferred):** cancel the in-flight payroll cycle
   via the Praxis admin UI. Submit manually through portal web UIs (the manual
   process that existed before any automation).
2. **Code rollback:** revert the Praxis SQS PR via `git revert`, redeploy via
   EB. Agent-worker team reverts theirs in parallel.
3. **No data rollback needed** — SQS queues survive a code rollback fine; messages
   in flight either get processed by the rolled-back worker or expire after the
   4-day retention.

The KMS keys and secrets do not get deleted in any rollback scenario (they're
expensive to recreate and harmless to leave).

---

## 10. Tracking Checklist

Update this section as work progresses. Tick items as they ship.

### Coordination
- [x] §6 addendum sent to agent-worker team (2026-05-05)
- [x] Round-1 feedback received (2026-05-06) — incorporated into §6.1–§6.7
- [x] Round-2 reply sent to agent-worker team (2026-05-06)
- [x] Round-2 feedback received (2026-05-06): aligned-to-execute + 6 residual issues — incorporated as round-3 patch
- [x] Round-3 reply with Q1–Q8 sent to agent-worker team (2026-05-06)
- [x] Round-3 calibration + answers received (2026-05-06): all 8 Qs answered, Q6b surfaced as required worker-side store upgrade (precondition added to §10 below)
- [x] §11 Q1–Q8 all resolved (see §11)
- [x] §6.6 Q1, Q2, Q4, Q5, Q6a, Q7, Q8 locked
- [x] §6.7 payload-size decision: **Option A (raw SQS)**, watch threshold 180 KB cleartext
- [x] Naming convention locked
- [x] **Contract LOCKED 2026-05-06** — code work in §5 + agent-worker checklist below may now proceed

### Praxis code (§5)
- [x] Maven dependencies updated (add `spring-cloud-aws-starter-sqs`, remove `spring-boot-starter-amqp`) — shipped 2026-05-06
- [x] `application.yml` and `application-aws.yml` config rewritten — shipped 2026-05-06
- [x] `application-localstack.yml` profile added — shipped 2026-05-06
- [x] `AgentWorkerRabbitMQConfiguration` deleted — shipped 2026-05-06
- [x] `AgentWorkerSqsConfiguration` added (queue-name helpers) — shipped 2026-05-06
- [x] `PayrollServiceTaskDelegate` rewired to `SqsTemplate` — shipped 2026-05-06
- [x] `PayrollResultListener` rewired to `@SqsListener` — shipped 2026-05-06
- [x] BPMN Receive Task boundary timers bumped `PT10M` → `PT30M` in [payroll-cycle.bpmn20.xml](../src/main/resources/bpmn/payroll-cycle.bpmn20.xml) (per §11 Q3 resolution) — shipped 2026-05-06
- [x] `KmsConfiguration` alias-pattern default updated to env-aware (`alias/dev-payroll-firm-%d`) — shipped 2026-05-06
- [x] Unit tests converted (mock `SqsTemplate`) — shipped 2026-05-06
- [x] `PayrollSqsRoundTripIT` integration test added (LocalStack via Testcontainers) — shipped 2026-05-06
- [x] `services.yml` rewritten (drop rabbit + vault, add localstack) — shipped 2026-05-06
- [x] `localstack-init.sh` created (auto-creates dev queues/aliases/secrets) — shipped 2026-05-06
- [x] Surefire `<exclude>**/*IT*</exclude>` → `**/*IT.java` + `**/*IntTest.java` fixed (incidental) — shipped 2026-05-06
- [x] All existing tests passing (surefire unit tests pass; `*IT` tests require CI/Linux via failsafe — Windows Docker pipe incompatibility, same as Praxis `PayrollSqsRoundTripIT`)
- [x] Maven `dependency:tree | grep amqp` returns nothing — confirmed 2026-05-06

### Agent-worker code (their half)
- [x] `@RabbitListener` → `@SqsListener` swapped on both `PayrollTaskListener` and `PayrollCaptureListener` — shipped 2026-05-06
- [x] Result publisher swapped (`RabbitTemplate` → `SqsRetryablePublisher` wrapping `SqsTemplate`) — shipped 2026-05-06
- [x] Message attributes correctly populated per §6.2 mapping (`businessKey`, `schemaName`, `envelopeId`) — shipped 2026-05-06
- [x] **`InMemoryIdempotencyStore` upgraded** from `Cache<String, Boolean>` → `Cache<String, Object>` (stores full `PayrollSubmitResult` envelope). On duplicate hit, re-publish the cached envelope (NOT a synthetic `DUPLICATE_ENVELOPE` failure). Memory cap: 10K entries × ~10KB ≈ 100MB. Precondition of round-2 §6.3 recovery contract — shipped 2026-05-06
- [x] Bounded retry-with-backoff (3 attempts, exponential 1s/2s/4s) added around `SqsTemplate.send` in `SqsRetryablePublisher` **before** letting the listener throw — shipped 2026-05-06
- [x] Their tests passing (52/52 unit tests; `PayrollSqsRoundTripIT` IT requires CI/Linux via failsafe — Windows Docker pipe incompatibility documented)

### AWS resources — DEV (§4, dev-prefixed)
- [ ] DLQ created: `dev-financeagent-dlq`
- [ ] 7 working queues created with redrive policy + tags
- [ ] KMS key + alias `alias/dev-payroll-firm-1` created
- [ ] Mock secrets created under `dev/financeagent/...`
- [ ] `PraxisDeveloperLocal` policy created and attached to your IAM user
- [ ] Smoke: `aws sqs list-queues --queue-name-prefix dev-financeagent` returns 8 entries

### AWS resources — PROD (§4, prod-prefixed)
- [ ] DLQ created: `prod-financeagent-dlq`
- [ ] 7 working queues created with redrive policy + tags
- [ ] KMS key + alias `alias/prod-payroll-firm-1` created (manual NeoProc backfill)
- [ ] `PraxisSqsFinanceagent` policy created and attached to EB instance profile
- [ ] `PraxisKmsEnvelope` policy updated (alias prefix → `alias/prod-payroll-firm-*`)
- [ ] `PraxisSecretsManagerFinanceagent` policy updated (resource → `prod/financeagent/*`)
- [ ] `AgentWorkerSqsFinanceagent` + `AgentWorkerSecretsFinanceagent` policies created and attached to agent-worker Fargate task role
- [ ] **`AgentWorkerKmsDecrypt` policy** — created and attached **iff** Q8 lands as "flip encryption with SQS migration". If Q8 lands as "defer", record creation as named precondition of the encryption-enable item in `docs/EnhancementsBacklog.md` (§6.5 sequencing rule).

### Documentation
- [x] `docs/LocalDevPayrollE2E.md` rewritten for SQS / LocalStack — shipped 2026-05-06
- [x] `docs/PraxisIntegrationHandoff.md` §C.1 / §C.2 rewritten (marked SHIPPED, SQS topology addendum inline) — shipped 2026-05-06
- [x] `docs/Phase8SliceProvisioning.md` §3 (Amazon MQ) marked SUPERSEDED 2026-05-06 with redirect to `SqsMigrationPlan.md §4.1`; IAM section references new policies — shipped 2026-05-06
- [x] `docs/SqsDlqRunbook.md` created — shipped 2026-05-06
- [x] `CLAUDE.md` tech-stack section updated (RabbitMQ → AWS SQS, LocalStack in local dev) — shipped 2026-05-06
- [x] `MEMORY.md` Phase 11D entries updated — shipped 2026-05-06

### Cutover (§8)
- [x] Dev parity test passed (cross-system mock cycle on real dev queues) — **2026-05-14**: mock-payroll full capture + submit cycle completed end-to-end on real `dev-financeagent-*` queues. Praxis `waitCaptureResult` and `waitSubmitResult` both advanced. `businessKey=2026-04::1051` correlated correctly throughout.
- [x] **Visibility timeout re-checked against dev-parity actual timings** — mock-payroll capture: ~1.4–1.6s; submit: similarly fast. Both well under 900s baseline. No change needed.
- [x] **Largest envelope size monitored during dev-parity** — mock-payroll envelopes far below 180 KB threshold. Option A (raw SQS) confirmed safe.
- [ ] RDS snapshot taken
- [ ] Code merged to main + EB deployed
- [ ] Boot logs verified (KMS key found, SQS listeners started, no errors)
- [ ] Prod mock-payroll smoke cycle: round-trip OK, audit OK, no DLQ
- [ ] Prod first real cycle: success

### Cleanup (post-cutover)
- [ ] Cancel any pending Amazon MQ provisioning in AWS console (if started)
- [ ] Verify no `spring-amqp` or `rabbitmq` references remain in repo
- [ ] CloudWatch alarms set on DLQ + result-queue age (separate ticket)

---

## 11. Resolved Questions (was: Open Questions)

1. **Cross-account vs same-account for the agent-worker.** Per §6.6 Q2. Drives
   the IAM design — `Resource: arn:aws:sqs:us-east-1:<acct>:...` either uses
   our account ID throughout or requires a queue resource policy with a
   `Principal` block granting the worker's role ARN.

   **RESOLVED 2026-05-06:** ✅ Same-account. Worker is greenfield-AWS, deploys
   into NeoProc account. §6.5 wildcard ARNs stand as written.

2. **Visibility-timeout calibration.** Per §6.6 Q3. Plan currently shows 900s
   (15 min) as a placeholder bumped from the initial 600s draft. Final value
   depends on agent-worker team's P95/worst observed portal-interaction time
   from live runs. If any portal routinely runs > 15 min, either bump to
   1200s/1800s or document the `ChangeMessageVisibility` heartbeat pattern.

   **RESOLVED 2026-05-06 (with watch clause):** ✅ Keep 900s baseline. Single
   datapoint (INS RT-V 62s) gives 14-min headroom; CCSS / mock-payroll have
   no production timing yet. Re-check during dev-parity (§7.2); if any P95 >
   600s, bump before prod. Heartbeat documented as escape hatch.
3. **BPMN Receive Task timer relationship — RESOLVED 2026-05-06,
   confirmed by agent-worker team in round-3 review.** Existing BPMN sets
   the boundary timer to **10 min** (per [payroll-cycle.bpmn20.xml](../src/main/resources/bpmn/payroll-cycle.bpmn20.xml)),
   smaller than the proposed SQS visibility timeout (900s / 15 min) — wrong
   direction. **Decision: bump BPMN timer to 30 min** (`PT10M` → `PT30M` on
   each Receive Task). Rationale:
   1. Payroll cycles aren't real-time, the 20-min difference in human
      escalation latency is operationally insignificant.
   2. `BPMN >= 2× visibility` allows one full retry within the BPMN's
      patience window, cutting HITL noise from recoverable transient failures.
   3. Single XML attribute change vs. retuning visibility.
   4. **The genuine-stuck-worker latency increase is compensated by the
      §3.7 CloudWatch alarm** (`tasks-submit-*.ApproximateAgeOfOldestMessage > 600s`),
      which pages 20 min before the BPMN timer would fire. So operationally
      we notice dead workers at the 10-min mark just as before — only the
      "tried, failed once, retry succeeded" path benefits from the longer
      window.
   The `ChangeMessageVisibility` heartbeat (§6.3) covers any portal that
   routinely exceeds 15 min per attempt. To do as part of §5.3 code work.
4. **Payload size & SQS Extended Client.** Per §6.6 Q7 + §6.7. Default Option
   A (raw SQS, ≤256 KB body limit) unless agent-worker team's data forces
   Option B (S3-backed extended client). Decide before any code ships.

   **RESOLVED 2026-05-06 (with watch threshold):** ✅ Option A (raw SQS).
   Largest observed today ~8 KB cleartext; projected 12-mo ceiling ~150 KB
   cleartext / ~200 KB encrypted — comfortable margin. Watch threshold:
   any single envelope > 180 KB cleartext flips us to Option B before next
   cycle.

5. **Idempotency store survival AND result-body caching.** Per §6.6 Q6.
   Whether process-local Caffeine on the worker is sufficient end-to-end,
   AND whether the store caches full result bodies vs. just envelopeId
   markers. Round-2 §6.3 recovery contract requires full-body caching.

   **RESOLVED 2026-05-06:**
   - **Q6a (restart survival):** ✅ Process-local Caffeine sufficient for
     v1; persistent backing store (Redis/DynamoDB) tracked in
     `EnhancementsBacklog.md` if cycle frequency or volume increases.
   - **Q6b (result-body caching):** ⚠️ Currently marker-only — required
     upgrade. Worker upgrades store from `Cache<String, Boolean>` →
     `Cache<String, ResultEnvelope>` + bounded retry around
     `SqsTemplate.send`. Tracked in §10 agent-worker checklist as a
     precondition of round-2 §6.3 recovery contract. **Without this
     upgrade, round-2 §6.3 wording is technically aspirational.**

6. **Encryption ETA.** Per §6.6 Q8. Flip with the SQS migration or after?
   Affects payload sizing (~33% inflation) and worker-side `AwsKmsCipher`
   implementation timing.

   **RESOLVED 2026-05-06:** ✅ Defer. Sequencing: SQS migration →
   `ciphertextField` schema fix → `AwsKmsCipher` + flip. New entry in
   `EnhancementsBacklog.md` tracks the encryption-enable item with
   `AgentWorkerKmsDecrypt` policy creation as a named precondition.
7. **Per-developer queue prefixes.** Currently planning a single shared
   `dev-` prefix. If multiple developers ever work on Praxis simultaneously
   we'd need `dev-hzunigav-financeagent-*` etc. Punt until real problem.
8. **Backfill `KmsKeyProvisioningListener` for NeoProc.** The listener fires
   on `FirmCreatedEvent` only; NeoProc was created pre-Phase-11B so it never
   fired. Plan handles this via manual `aws kms create-key` in §4.2, but
   should the listener gain a `@PostConstruct` backfill that idempotently
   provisions for all existing firms? Tracked separately if so.
9. **CloudWatch alarms** — wired up before or after cutover? Lean toward
   before (so DLQ messages page immediately) but adds another item to Week 3.
10. **IaC follow-up.** Manual provisioning is fine for the small set of
    resources here, but a Terraform module would prevent dev/prod drift.
    Tracked separately.
