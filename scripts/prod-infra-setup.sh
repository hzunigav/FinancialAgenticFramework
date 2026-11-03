#!/usr/bin/env bash
# prod-infra-setup.sh — one-time provisioning for a new agent-worker portal in production.
#
# Run from AWS CloudShell. Idempotent: safe to re-run; existing resources are skipped.
#
# Usage:
#   ./scripts/prod-infra-setup.sh <portal-id> <queue-type> <image-tag>
#
#   portal-id   Portal identifier matching the YAML descriptor filename, e.g. ccss-sicere
#   queue-type  Which task queues to create: capture | submit | both | bankstatement
#   image-tag   ECR image tag to use in the initial task definition, e.g. prod-abc1234
#
#   bankstatement (Xero) creates a dedicated task queue + a dedicated results
#   queue (the payroll results queue is shared and provisioned elsewhere), and
#   wires the Xero-only env (browser channel for the Akamai-stealth launch).
#
# Environment (set these before running, or export them):
#   CLUSTER          ECS cluster name       (default: financeagent)
#   REGION           AWS region             (default: us-east-1)
#   ACCOUNT          AWS account ID         (default: 409159414704)
#   SUBNET_ID        Subnet(s) for Fargate tasks, comma-separated. These are the
#                    PUBLIC subnets (default route to the internet gateway) — see
#                    Step 4 for why tasks get a public IP rather than a NAT.
#   SG_ID            Security group ID for Fargate tasks
#   EXEC_ROLE_ARN    ECS task execution role ARN (ECR pull + CloudWatch logs)
#   TASK_ROLE_ARN    ECS task role ARN (SQS, KMS, Secrets Manager)
#
# To retrieve these values from an existing service:
#   source <(./scripts/prod-infra-setup.sh --print-env)
#
# New services are created at desired-count 0 and tagged for the queue-driven
# scaler (scripts/scaling-lambda.py), which starts a task when a message lands.
#
set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
CLUSTER="${CLUSTER:-financeagent}"
REGION="${REGION:-us-east-1}"
ACCOUNT="${ACCOUNT:-409159414704}"
ENV_PREFIX="prod"
DLQ_NAME="${ENV_PREFIX}-financeagent-dlq"
ARTIFACTS_BUCKET="${ENV_PREFIX}-financeagent-artifacts"

# ── Helper: print env vars from an existing service ───────────────────────────
if [[ "${1:-}" == "--print-env" ]]; then
  # Read from the service definition, not a running task: with scale-to-zero
  # there is usually no task to inspect.
  SVC="${2:-financeagent-worker-ccss-sicere}"
  SVC_JSON=$(aws ecs describe-services --cluster "$CLUSTER" --services "$SVC" \
    --region "$REGION" --query 'services[0]' --output json)
  TD_ARN=$(echo "$SVC_JSON" | jq -r '.taskDefinition')
  TD_JSON=$(aws ecs describe-task-definition --task-definition "$TD_ARN" \
    --region "$REGION" --query 'taskDefinition' --output json)
  echo "export SUBNET_ID=$(echo "$SVC_JSON" | jq -r '.networkConfiguration.awsvpcConfiguration.subnets | join(",")')"
  echo "export SG_ID=$(echo "$SVC_JSON"     | jq -r '.networkConfiguration.awsvpcConfiguration.securityGroups[0]')"
  echo "export EXEC_ROLE_ARN=$(echo "$TD_JSON" | jq -r '.executionRoleArn')"
  echo "export TASK_ROLE_ARN=$(echo "$TD_JSON" | jq -r '.taskRoleArn')"
  exit 0
fi

# ── Argument validation ────────────────────────────────────────────────────────
if [[ $# -lt 3 ]]; then
  echo "Usage: $0 <portal-id> <capture|submit|both|bankstatement> <image-tag>" >&2
  exit 1
fi

PORTAL_ID="$1"
QUEUE_TYPE="$2"   # capture | submit | both
IMAGE_TAG="$3"
IMAGE_URI="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com/financeagent-worker:${IMAGE_TAG}"

for VAR in SUBNET_ID SG_ID EXEC_ROLE_ARN TASK_ROLE_ARN; do
  if [[ -z "${!VAR:-}" ]]; then
    echo "ERROR: $VAR is not set. Run: source <(./scripts/prod-infra-setup.sh --print-env)" >&2
    exit 1
  fi
done

# ── Service naming ──────────────────────────────────────────────────────────
# Payroll flows use financeagent-worker-<portal>. The bankstatement flow uses
# financeagent-bankstatement-<portal> instead: it mirrors the queue naming
# (bankstatement- is the flow-distinguishing token). Drives the service name,
# the task-definition family, and the log group below. Scaling no longer keys
# on the name — see the financeagent:task-queues tag in Step 4.
if [[ "$QUEUE_TYPE" == "bankstatement" ]]; then
  SERVICE_BASENAME="financeagent-bankstatement-${PORTAL_ID}"
else
  SERVICE_BASENAME="financeagent-worker-${PORTAL_ID}"
fi

echo "=== Provisioning portal: $PORTAL_ID (queue-type=$QUEUE_TYPE, service=$SERVICE_BASENAME) ==="

# ── Step 0: S3 artifacts bucket (idempotent, one-time per env) ────────────────
# Holds post-run artifact uploads from every worker:
#   s3://${ARTIFACTS_BUCKET}/${ENV_PREFIX}/runs/<portal>/<runId>/{manifest.json,report.png,network.har,...}
# Lifecycle expires objects at 90 days — adjust if compliance requires longer
# retention. No versioning by design (artifacts are immutable per-run anyway).
if aws s3api head-bucket --bucket "$ARTIFACTS_BUCKET" --region "$REGION" 2>/dev/null; then
  echo "  Artifacts bucket already exists: $ARTIFACTS_BUCKET"
else
  if [[ "$REGION" == "us-east-1" ]]; then
    aws s3api create-bucket --bucket "$ARTIFACTS_BUCKET" --region "$REGION" > /dev/null
  else
    aws s3api create-bucket --bucket "$ARTIFACTS_BUCKET" --region "$REGION" \
      --create-bucket-configuration "LocationConstraint=$REGION" > /dev/null
  fi
  aws s3api put-public-access-block --bucket "$ARTIFACTS_BUCKET" --region "$REGION" \
    --public-access-block-configuration \
    "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true" \
    > /dev/null
  echo "  Created artifacts bucket: $ARTIFACTS_BUCKET"
fi
aws s3api put-bucket-lifecycle-configuration --bucket "$ARTIFACTS_BUCKET" --region "$REGION" \
  --lifecycle-configuration '{"Rules":[{"ID":"expire-90d","Status":"Enabled","Expiration":{"Days":90},"Filter":{"Prefix":""}}]}' \
  > /dev/null

# ── Step 1: SQS queues ────────────────────────────────────────────────────────
DLQ_ARN=$(aws sqs get-queue-attributes \
  --queue-url "$(aws sqs get-queue-url --queue-name "$DLQ_NAME" --region "$REGION" --query 'QueueUrl' --output text)" \
  --attribute-names QueueArn --region "$REGION" \
  --query 'Attributes.QueueArn' --output text)

# VisibilityTimeout must exceed the LONGEST run this queue carries, not the
# average. If it lapses mid-run SQS redelivers while the first run is still
# driving the portal — and CCSS/INS permit only one active session per
# account, so the redelivery collides with the run it duplicated. 1800s
# covers a large payroll (per-row Aplicar over a big roster); a report pull
# finishes in ~90s. Raise this before onboarding anything slower.
#
# The cost of a high value is slower recovery: if a worker dies mid-message,
# nothing retries it until the timeout lapses.
TASK_VISIBILITY_TIMEOUT=1800

create_queue() {
  local NAME="$1"
  if QURL=$(aws sqs get-queue-url --queue-name "$NAME" --region "$REGION" \
              --query QueueUrl --output text 2>/dev/null); then
    # Enforced on existing queues too: queues created before the raise to
    # 1800 s otherwise keep the old 900 s forever, since this branch used to
    # return without touching them.
    aws sqs set-queue-attributes --queue-url "$QURL" --region "$REGION" \
      --attributes "VisibilityTimeout=${TASK_VISIBILITY_TIMEOUT}"
    echo "  Queue already exists: $NAME (VisibilityTimeout=${TASK_VISIBILITY_TIMEOUT} enforced)"
    return
  fi
  aws sqs create-queue --queue-name "$NAME" --region "$REGION" \
    --attributes "{
      \"VisibilityTimeout\":           \"${TASK_VISIBILITY_TIMEOUT}\",
      \"ReceiveMessageWaitTimeSeconds\":\"20\",
      \"MessageRetentionPeriod\":      \"345600\",
      \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"$DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"
    }" > /dev/null
  echo "  Created queue: $NAME"
}

[[ "$QUEUE_TYPE" == "capture" || "$QUEUE_TYPE" == "both" ]] && \
  create_queue "${ENV_PREFIX}-financeagent-tasks-capture-${PORTAL_ID}"

[[ "$QUEUE_TYPE" == "submit" || "$QUEUE_TYPE" == "both" ]] && \
  create_queue "${ENV_PREFIX}-financeagent-tasks-submit-${PORTAL_ID}"

# Bank-statement (Xero) flow: a dedicated task queue (DLQ-backed) plus a
# dedicated results queue. Unlike payroll, the bankstatement results queue is
# NOT shared, so we create it here. A results queue has no redrive — the worker
# only produces to it and Praxis consumes; a poison result should surface, not
# silently DLQ.
create_results_queue() {
  local NAME="$1"
  if aws sqs get-queue-url --queue-name "$NAME" --region "$REGION" &>/dev/null; then
    echo "  Queue already exists: $NAME"
    return
  fi
  aws sqs create-queue --queue-name "$NAME" --region "$REGION" \
    --attributes "{
      \"VisibilityTimeout\":            \"60\",
      \"ReceiveMessageWaitTimeSeconds\": \"20\",
      \"MessageRetentionPeriod\":       \"345600\"
    }" > /dev/null
  echo "  Created results queue: $NAME"
}

if [[ "$QUEUE_TYPE" == "bankstatement" ]]; then
  create_queue         "${ENV_PREFIX}-financeagent-tasks-bankstatement-${PORTAL_ID}"
  create_results_queue "${ENV_PREFIX}-financeagent-bankstatement-results"
fi

# ── Step 2: CloudWatch log group ──────────────────────────────────────────────
LOG_GROUP="/ecs/${SERVICE_BASENAME}"
if aws logs describe-log-groups --log-group-name-prefix "$LOG_GROUP" --region "$REGION" \
    --query "logGroups[?logGroupName=='$LOG_GROUP']" --output text | grep -q .; then
  echo "  Log group already exists: $LOG_GROUP"
else
  aws logs create-log-group --log-group-name "$LOG_GROUP" --region "$REGION"
  echo "  Created log group: $LOG_GROUP"
fi

# ── Step 3: Task definition ───────────────────────────────────────────────────
SERVICE_NAME="${SERVICE_BASENAME}"

# Build environment array; add MOCK_PORTAL_BASE_URL only for mock-payroll
ENV_JSON="[
  {\"name\":\"PORTAL_ID\",                        \"value\":\"${PORTAL_ID}\"},
  {\"name\":\"AWS_REGION\",                       \"value\":\"${REGION}\"},
  {\"name\":\"FINANCEAGENT_CIPHER\",              \"value\":\"cleartext\"},
  {\"name\":\"FINANCEAGENT_CREDENTIALS\",         \"value\":\"aws\"},
  {\"name\":\"FINANCEAGENT_SECRETS_ENV_PREFIX\",  \"value\":\"${ENV_PREFIX}\"},
  {\"name\":\"ARTIFACTS_BUCKET\",                 \"value\":\"${ARTIFACTS_BUCKET}\"},
  {\"name\":\"JAVA_TOOL_OPTIONS\",                \"value\":\"-Dagent.worker.queue-prefix=${ENV_PREFIX}\"}
]"

# Xero (bankstatement): the adapter launches a STEALTH browser to clear Akamai
# Bot Manager. XERO_BROWSER_CHANNEL is set EMPTY here so the worker uses the
# image's BUNDLED Chromium rather than branded Chrome: the playwright/java image
# has no branded Chrome, and it isn't available for ARM64 Linux anyway. Validated
# 2026-06-20 (XeroAkamaiProbe inside this exact image): bundled Chromium + the
# stealth launch, headless, renders the Xero login with NO Akamai block. Locally
# the code defaults to channel=chrome (devs have it); the empty env overrides it
# in-container. The shared Xero login + TOTP seed come from Secrets Manager via
# FINANCEAGENT_CREDENTIALS=aws (path: <prefix>/financeagent/shared/portals/xero),
# and the persisted browser session is stored via the AWS Secrets-Manager+KMS
# SessionStore so it survives scale-to-zero.
if [[ "$PORTAL_ID" == "xero" ]]; then
  ENV_JSON=$(echo "$ENV_JSON" | jq '. += [{"name":"XERO_BROWSER_CHANNEL","value":""}]')
fi

# stopTimeout 120 is the Fargate maximum. An in-flight run is protected by ECS
# task scale-in protection (TaskProtectionInterceptor), not by this; it only
# gives the SQS container time to flush acknowledgements once a task is idle.
TD_JSON=$(jq -n \
  --arg family   "$SERVICE_NAME" \
  --arg execRole "$EXEC_ROLE_ARN" \
  --arg taskRole "$TASK_ROLE_ARN" \
  --arg image    "$IMAGE_URI" \
  --arg logGroup "$LOG_GROUP" \
  --arg region   "$REGION" \
  --argjson env  "$ENV_JSON" \
'{
  family:                    $family,
  executionRoleArn:          $execRole,
  taskRoleArn:               $taskRole,
  networkMode:               "awsvpc",
  requiresCompatibilities:   ["FARGATE"],
  cpu:                       "1024",
  memory:                    "3072",
  runtimePlatform: {
    cpuArchitecture:        "ARM64",
    operatingSystemFamily:  "LINUX"
  },
  containerDefinitions: [{
    name:      "agent-worker",
    image:     $image,
    essential: true,
    stopTimeout: 120,
    environment: $env,
    logConfiguration: {
      logDriver: "awslogs",
      options: {
        "awslogs-group":         $logGroup,
        "awslogs-region":        $region,
        "awslogs-stream-prefix": "ecs"
      }
    }
  }]
}')

TD_ARN=$(aws ecs register-task-definition \
  --cli-input-json "$TD_JSON" --region "$REGION" \
  --query 'taskDefinition.taskDefinitionArn' --output text)
echo "  Registered task definition: $TD_ARN"

# ── Step 4: ECS service ───────────────────────────────────────────────────────
# Queues the queue-driven scaler watches for this service. Must be exactly the
# queues the worker CONSUMES, which the portal descriptor's `flows` decides —
# pick queue-type to match it. A listed queue the worker never polls would
# hold the service at one task forever, since nothing would drain it.
case "$QUEUE_TYPE" in
  capture)       TASK_QUEUES="${ENV_PREFIX}-financeagent-tasks-capture-${PORTAL_ID}" ;;
  submit)        TASK_QUEUES="${ENV_PREFIX}-financeagent-tasks-submit-${PORTAL_ID}" ;;
  both)          TASK_QUEUES="${ENV_PREFIX}-financeagent-tasks-capture-${PORTAL_ID} ${ENV_PREFIX}-financeagent-tasks-submit-${PORTAL_ID}" ;;
  bankstatement) TASK_QUEUES="${ENV_PREFIX}-financeagent-tasks-bankstatement-${PORTAL_ID}" ;;
  *) echo "ERROR: unknown queue-type $QUEUE_TYPE" >&2; exit 1 ;;
esac
# JSON, not key=...,value=... shorthand: the value contains spaces.
QUEUE_TAG_JSON=$(jq -cn --arg v "$TASK_QUEUES" '[{key:"financeagent:task-queues",value:$v}]')

EXISTING_STATUS=$(aws ecs describe-services \
  --cluster "$CLUSTER" --services "$SERVICE_NAME" --region "$REGION" \
  --query 'services[0].status' --output text 2>/dev/null || echo "MISSING")

if [[ "$EXISTING_STATUS" == "ACTIVE" ]]; then
  echo "  Service already exists: $SERVICE_NAME — updating task definition"
  aws ecs update-service \
    --cluster "$CLUSTER" --service "$SERVICE_NAME" \
    --task-definition "$TD_ARN" --force-new-deployment \
    --region "$REGION" --no-cli-pager > /dev/null
  SERVICE_ARN=$(aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE_NAME" \
    --region "$REGION" --query 'services[0].serviceArn' --output text)
  aws ecs tag-resource --resource-arn "$SERVICE_ARN" --region "$REGION" \
    --tags "$QUEUE_TAG_JSON"
else
  # Network: public subnets + a public IP per task. The tasks need outbound
  # access to the portals, ECR, SQS, S3 and Secrets Manager; a NAT gateway
  # would cost ~$33/mo + data, while a public IP costs $0.005/h only while a
  # task runs — near nothing with scale-to-zero. Do NOT set DISABLED in these
  # subnets: with no NAT, the task can't even pull its image (this is what
  # kept the old testing-harness service from starting).
  #
  # Desired count 0: the queue-driven scaler starts a task when a message
  # arrives. No Service Connect: workers don't call one another.
  aws ecs create-service \
    --cluster "$CLUSTER" \
    --service-name "$SERVICE_NAME" \
    --task-definition "$TD_ARN" \
    --desired-count 0 \
    --launch-type FARGATE \
    --network-configuration "awsvpcConfiguration={subnets=[${SUBNET_ID}],securityGroups=[${SG_ID}],assignPublicIp=ENABLED}" \
    --tags "$QUEUE_TAG_JSON" \
    --region "$REGION" --no-cli-pager > /dev/null
  echo "  Created ECS service: $SERVICE_NAME (desired 0, scaled on: ${TASK_QUEUES})"
fi

echo ""
echo "=== Done. Portal $PORTAL_ID is provisioned. ==="
echo "    Push code to main to deploy the first image via GitHub Actions."
echo "    To update AgentWorkerSqsFinanceagent IAM policy, add the new queue ARN to the ConsumeTasks statement."
echo ""
echo "    IAM (one-time per env): the task role must allow s3:PutObject on the artifacts bucket."
echo "    Add this statement to AgentWorkerSqsFinanceagent (or attach a separate"
echo "    AgentWorkerArtifactsFinanceagent managed policy):"
echo ""
echo "      { \"Effect\": \"Allow\","
echo "        \"Action\": [\"s3:PutObject\"],"
echo "        \"Resource\": \"arn:aws:s3:::${ARTIFACTS_BUCKET}/*\" }"
echo ""
echo "    Praxis (read-side): grant the Praxis BPM execution role s3:GetObject + s3:ListBucket"
echo "    on arn:aws:s3:::${ARTIFACTS_BUCKET} so workflow steps can fetch audit.manifestPath."
echo ""
echo "    Scale-to-zero (one-time per env): the task role must allow ecs:UpdateTaskProtection"
echo "    (TaskProtectionInterceptor), and scripts/setup-scaling-schedule.sh must have run."

if [[ "$QUEUE_TYPE" == "bankstatement" ]]; then
  echo ""
  echo "  === Xero (bankstatement) extra steps ==="
  echo "    1. Secrets Manager — create the shared login + TOTP seed (JSON key-value map;"
  echo "       AwsSecretsManagerCredentialsProvider resolves shared scope to this exact path):"
  echo "         aws secretsmanager create-secret \\"
  echo "           --name ${ENV_PREFIX}/financeagent/shared/portals/xero \\"
  echo "           --secret-string '{\"username\":\"...\",\"password\":\"...\",\"totpSeed\":\"BASE32SEED\"}'"
  echo "    2. Session secret — ${ENV_PREFIX}/financeagent/sessions/xero is created/updated by the"
  echo "       worker itself (AwsSecretsManagerSessionStore). Optionally pre-create it, and to use"
  echo "       a customer-managed KMS key set FINANCEAGENT_SESSION_KMS_KEY_ID on the task def."
  echo "       (Optional) seed it once via SessionSeeder so the FIRST prod run skips 2FA."
  echo "    3. IAM — the task role needs:"
  echo "         secretsmanager:GetSecretValue on  ${ENV_PREFIX}/financeagent/shared/portals/xero"
  echo "         secretsmanager:GetSecretValue/PutSecretValue/CreateSecret/DeleteSecret on"
  echo "                                           ${ENV_PREFIX}/financeagent/sessions/xero"
  echo "         kms:Encrypt/Decrypt/GenerateDataKey on the session KMS key (if a CMK is used)"
  echo "         sqs:ReceiveMessage/DeleteMessage/GetQueueAttributes on the task queue ARN"
  echo "           arn:aws:sqs:${REGION}:${ACCOUNT}:${ENV_PREFIX}-financeagent-tasks-bankstatement-${PORTAL_ID}"
  echo "         sqs:SendMessage on the results queue ARN"
  echo "           arn:aws:sqs:${REGION}:${ACCOUNT}:${ENV_PREFIX}-financeagent-bankstatement-results"
  echo "       (add to AgentWorkerSqsFinanceagent ConsumeTasks / PublishResults statements)."
  echo ""
  echo "    Akamai + headless: RESOLVED 2026-06-20. The stealth launch with the image's"
  echo "    BUNDLED Chromium (XERO_BROWSER_CHANNEL empty, set above) renders the Xero login"
  echo "    headless with no Akamai block — verified via XeroAkamaiProbe inside this exact"
  echo "    playwright/java:v1.48.0-jammy image. No branded-Chrome layer or xvfb needed; stay"
  echo "    on ARM64. Re-run the probe if the base image or Playwright version changes."
fi
