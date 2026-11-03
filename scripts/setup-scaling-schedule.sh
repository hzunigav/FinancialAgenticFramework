#!/usr/bin/env bash
# setup-scaling-schedule.sh — one-time provisioning for queue-driven
# scale-to-zero of the financeagent ECS services.
#
# Creates or updates (idempotently):
#   - IAM role for the scaling Lambda
#   - Lambda function `financeagent-scaling` (code: scaling-lambda.py)
#   - IAM role for EventBridge Scheduler (lambda:InvokeFunction)
#   - One schedule, financeagent-queue-scaler, rate(1 minute)
# and removes the calendar schedules earlier versions of this script defined.
#
# Which services scale, and on which queues, is decided by the service tag
# financeagent:task-queues (set by prod-infra-setup.sh). See the docstring in
# scaling-lambda.py for the decision rules.
#
# PREREQUISITE: deploy a worker image that includes TaskProtectionInterceptor
# before enabling this. Scale-in is already held off while a message is in
# flight, but task protection closes the remaining race between a scale-in and
# a message received in the same instant.
#
# Run from AWS CloudShell, from the repo root.
#
# Cost: Lambda (~43k invocations/month) and Scheduler are inside the free tier;
# GetQueueAttributes adds ~43k SQS requests per queue per month (~$0.02 per
# queue beyond the 1M free requests).
#
# Env overrides:
#   IDLE_MINUTES=5    idle time before a service scales to 0
#   DRY_RUN=true      Lambda logs decisions but changes nothing (good first run)
#   QUEUE_PATTERN=prod-financeagent-tasks-*   queues the Lambda may read
set -euo pipefail

REGION="${REGION:-us-east-1}"
ACCOUNT="${ACCOUNT:-409159414704}"
CLUSTER="${CLUSTER:-financeagent}"
IDLE_MINUTES="${IDLE_MINUTES:-5}"
DRY_RUN="${DRY_RUN:-false}"
QUEUE_PATTERN="${QUEUE_PATTERN:-prod-financeagent-tasks-*}"

LAMBDA_NAME="financeagent-scaling"
LAMBDA_ROLE_NAME="FinanceAgentScalingLambdaRole"
SCHEDULER_ROLE_NAME="FinanceAgentSchedulerRole"
SCHEDULE_NAME="financeagent-queue-scaler"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LAMBDA_SRC="$SCRIPT_DIR/scaling-lambda.py"

if [[ ! -f "$LAMBDA_SRC" ]]; then
  echo "ERROR: $LAMBDA_SRC not found — run this script from the repo root" >&2
  exit 1
fi

# ── 1. Lambda execution role ──────────────────────────────────────────────────
echo "── Lambda IAM role ──"
LAMBDA_TRUST='{
  "Version":"2012-10-17",
  "Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]
}'

if aws iam get-role --role-name "$LAMBDA_ROLE_NAME" &>/dev/null; then
  echo "  Role exists: $LAMBDA_ROLE_NAME"
else
  aws iam create-role --role-name "$LAMBDA_ROLE_NAME" \
    --assume-role-policy-document "$LAMBDA_TRUST" > /dev/null
  echo "  Created role: $LAMBDA_ROLE_NAME"
fi

aws iam attach-role-policy --role-name "$LAMBDA_ROLE_NAME" \
  --policy-arn "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole" > /dev/null

# TagResource/UntagResource: the Lambda keeps each service's idle-since stamp
# as a tag on the service, so it needs no state store of its own.
LAMBDA_POLICY="{
  \"Version\":\"2012-10-17\",
  \"Statement\":[
    {
      \"Effect\":\"Allow\",
      \"Action\":[\"ecs:UpdateService\",\"ecs:DescribeServices\",\"ecs:TagResource\",\"ecs:UntagResource\"],
      \"Resource\":\"arn:aws:ecs:${REGION}:${ACCOUNT}:service/${CLUSTER}/*\"
    },
    {
      \"Effect\":\"Allow\",
      \"Action\":\"ecs:ListServices\",
      \"Resource\":\"*\"
    },
    {
      \"Effect\":\"Allow\",
      \"Action\":[\"sqs:GetQueueUrl\",\"sqs:GetQueueAttributes\"],
      \"Resource\":\"arn:aws:sqs:${REGION}:${ACCOUNT}:${QUEUE_PATTERN}\"
    }
  ]
}"
aws iam put-role-policy --role-name "$LAMBDA_ROLE_NAME" \
  --policy-name FinanceAgentScalingEcsAccess \
  --policy-document "$LAMBDA_POLICY" > /dev/null

LAMBDA_ROLE_ARN=$(aws iam get-role --role-name "$LAMBDA_ROLE_NAME" --query 'Role.Arn' --output text)
echo "  ARN: $LAMBDA_ROLE_ARN"

# ── 2. Lambda function ────────────────────────────────────────────────────────
echo "── Lambda function ──"
TMP=$(mktemp -d)
cp "$LAMBDA_SRC" "$TMP/lambda_function.py"
(cd "$TMP" && zip -q lambda.zip lambda_function.py)

ENV_VARS="Variables={CLUSTER=$CLUSTER,IDLE_MINUTES=$IDLE_MINUTES,DRY_RUN=$DRY_RUN}"

if aws lambda get-function --function-name "$LAMBDA_NAME" --region "$REGION" &>/dev/null; then
  aws lambda update-function-code --function-name "$LAMBDA_NAME" \
    --zip-file "fileb://$TMP/lambda.zip" --region "$REGION" --no-cli-pager > /dev/null
  aws lambda wait function-updated --function-name "$LAMBDA_NAME" --region "$REGION"
  aws lambda update-function-configuration --function-name "$LAMBDA_NAME" \
    --environment "$ENV_VARS" --region "$REGION" --no-cli-pager > /dev/null
  echo "  Updated: $LAMBDA_NAME"
else
  # IAM propagation: role may not be usable for ~10s after creation
  for i in 1 2 3 4 5 6; do
    if aws lambda create-function \
      --function-name "$LAMBDA_NAME" \
      --runtime python3.12 \
      --role "$LAMBDA_ROLE_ARN" \
      --handler lambda_function.lambda_handler \
      --zip-file "fileb://$TMP/lambda.zip" \
      --timeout 50 \
      --memory-size 128 \
      --environment "$ENV_VARS" \
      --region "$REGION" --no-cli-pager > /dev/null 2>&1; then
      echo "  Created: $LAMBDA_NAME"
      break
    fi
    if [[ $i -eq 6 ]]; then
      echo "  ERROR: Lambda create-function failed after 6 attempts" >&2
      exit 1
    fi
    echo "  Waiting for IAM role propagation... ($i/6)"
    sleep 10
  done
fi
rm -rf "$TMP"

# Ticks every minute; keep the log group from growing forever.
aws logs create-log-group --log-group-name "/aws/lambda/$LAMBDA_NAME" --region "$REGION" 2>/dev/null || true
aws logs put-retention-policy --log-group-name "/aws/lambda/$LAMBDA_NAME" \
  --retention-in-days 30 --region "$REGION"

LAMBDA_ARN=$(aws lambda get-function --function-name "$LAMBDA_NAME" --region "$REGION" \
  --query 'Configuration.FunctionArn' --output text)

# ── 3. EventBridge Scheduler role ─────────────────────────────────────────────
echo "── Scheduler IAM role ──"
SCHEDULER_TRUST='{
  "Version":"2012-10-17",
  "Statement":[{"Effect":"Allow","Principal":{"Service":"scheduler.amazonaws.com"},"Action":"sts:AssumeRole"}]
}'

if aws iam get-role --role-name "$SCHEDULER_ROLE_NAME" &>/dev/null; then
  echo "  Role exists: $SCHEDULER_ROLE_NAME"
else
  aws iam create-role --role-name "$SCHEDULER_ROLE_NAME" \
    --assume-role-policy-document "$SCHEDULER_TRUST" > /dev/null
  echo "  Created role: $SCHEDULER_ROLE_NAME"
fi

SCHEDULER_POLICY="{
  \"Version\":\"2012-10-17\",
  \"Statement\":[{
    \"Effect\":\"Allow\",
    \"Action\":\"lambda:InvokeFunction\",
    \"Resource\":\"${LAMBDA_ARN}\"
  }]
}"
aws iam put-role-policy --role-name "$SCHEDULER_ROLE_NAME" \
  --policy-name FinanceAgentScalingInvokeLambda \
  --policy-document "$SCHEDULER_POLICY" > /dev/null

SCHEDULER_ROLE_ARN=$(aws iam get-role --role-name "$SCHEDULER_ROLE_NAME" --query 'Role.Arn' --output text)
echo "  ARN: $SCHEDULER_ROLE_ARN"

# ── 4. Remove the calendar schedules of earlier versions ─────────────────────
for OLD in financeagent-scale-up financeagent-scale-down \
           financeagent-monthly-scale-up financeagent-monthly-scale-down \
           financeagent-daily-scale-up financeagent-daily-scale-down; do
  if aws scheduler get-schedule --name "$OLD" --region "$REGION" &>/dev/null; then
    aws scheduler delete-schedule --name "$OLD" --region "$REGION" > /dev/null
    echo "  Deleted legacy schedule: $OLD"
  fi
done

# ── 5. The schedule ───────────────────────────────────────────────────────────
echo "── Schedule ──"
TARGET="{\"Arn\":\"${LAMBDA_ARN}\",\"RoleArn\":\"${SCHEDULER_ROLE_ARN}\",\"RetryPolicy\":{\"MaximumRetryAttempts\":0}}"
CMD="create-schedule"
if aws scheduler get-schedule --name "$SCHEDULE_NAME" --region "$REGION" &>/dev/null; then
  CMD="update-schedule"
fi
# No retries: a failed tick is superseded by the next one a minute later.
aws scheduler "$CMD" \
  --name "$SCHEDULE_NAME" \
  --schedule-expression "rate(1 minute)" \
  --flexible-time-window "Mode=OFF" \
  --target "$TARGET" \
  --region "$REGION" --no-cli-pager > /dev/null
echo "  $CMD: $SCHEDULE_NAME (rate(1 minute))"

echo ""
echo "=== Setup complete (IDLE_MINUTES=$IDLE_MINUTES, DRY_RUN=$DRY_RUN) ==="
echo ""
echo "Opted-in services (tag financeagent:task-queues):"
for ARN in $(aws ecs list-services --cluster "$CLUSTER" --region "$REGION" --query 'serviceArns[]' --output text); do
  Q=$(aws ecs list-tags-for-resource --resource-arn "$ARN" --region "$REGION" \
        --query "tags[?key=='financeagent:task-queues'].value | [0]" --output text)
  [[ "$Q" != "None" && -n "$Q" ]] && echo "  ${ARN##*/}: $Q"
done
echo ""
echo "Watch decisions (only ticks that change something are logged):"
echo "  aws logs tail /aws/lambda/$LAMBDA_NAME --follow --region $REGION"
echo ""
echo "Pause scaling (services keep their current desired count):"
echo "  aws scheduler get-schedule --name $SCHEDULE_NAME --region $REGION \\"
echo "    | jq '{Name,ScheduleExpression,FlexibleTimeWindow,Target,State:\"DISABLED\"}' \\"
echo "    | aws scheduler update-schedule --cli-input-json file:///dev/stdin --region $REGION"
