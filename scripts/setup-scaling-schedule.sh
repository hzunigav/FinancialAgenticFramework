#!/usr/bin/env bash
# setup-scaling-schedule.sh — one-time provisioning for calendar-based
# scale-to-zero of the financeagent ECS services across two cadences.
#
# Creates (idempotently):
#   - IAM role for the scaling Lambda (ecs:UpdateService + ecs:ListServices)
#   - Lambda function `financeagent-scaling` (code: scaling-lambda.py)
#   - IAM role for EventBridge Scheduler (lambda:InvokeFunction)
#   - Four schedules in America/Costa_Rica timezone:
#
#       MONTHLY (payroll workers + testing-harness, ~11 days/month active):
#         financeagent-monthly-scale-up   cron(0 0 L-2 * ? *)  — L-2 of prior month 00:00
#         financeagent-monthly-scale-down cron(0 0 9 * ? *)    — day 9 of new month 00:00
#         Prefixes: financeagent-worker-,testing-harness
#
#       DAILY (bank-statement portals such as Xero, ~2 hours/day active):
#         financeagent-daily-scale-up   cron(0 6 * * ? *)  — every day 06:00
#         financeagent-daily-scale-down cron(0 8 * * ? *)  — every day 08:00
#         Prefixes: financeagent-bankstatement-
#
# Each schedule passes its own prefixes in the event payload, so the same
# Lambda handles both cadences. New portals are picked up automatically by
# whichever schedule's prefix matches their service name.
#
# Run from AWS CloudShell. Requires zip (preinstalled) and scaling-lambda.py
# alongside this script.
#
# Cost impact (Graviton, us-east-1):
#   - 5 workers + harness on monthly cadence: ~$60/mo
#   - 1 Xero portal on daily 2-hour window:  ~$2.50/mo
#   EventBridge + Lambda invocations are free at this volume.
#
# Override the daily window with env vars:
#   DAILY_UP_HOUR=6      (default 06:00 CR scale-up)
#   DAILY_DOWN_HOUR=8    (default 08:00 CR scale-down)
#   DAILY_PREFIXES=financeagent-bankstatement-
set -euo pipefail

REGION="${REGION:-us-east-1}"
ACCOUNT="${ACCOUNT:-409159414704}"
CLUSTER="${CLUSTER:-financeagent}"
TIMEZONE="${TIMEZONE:-America/Costa_Rica}"

LAMBDA_NAME="financeagent-scaling"
LAMBDA_ROLE_NAME="FinanceAgentScalingLambdaRole"
SCHEDULER_ROLE_NAME="FinanceAgentSchedulerRole"

# Cadence configuration. Each cadence has its own service-name prefix list;
# the Lambda receives the relevant list in the schedule's event payload.
MONTHLY_PREFIXES="${MONTHLY_PREFIXES:-financeagent-worker-,testing-harness}"
DAILY_PREFIXES="${DAILY_PREFIXES:-financeagent-bankstatement-}"
DAILY_UP_HOUR="${DAILY_UP_HOUR:-6}"
DAILY_DOWN_HOUR="${DAILY_DOWN_HOUR:-8}"

# Fallback env var for ad-hoc Lambda invocations that omit `prefixes`.
# Schedules always supply prefixes per-event, so this is only a convenience.
FALLBACK_PREFIXES="$MONTHLY_PREFIXES"

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

LAMBDA_POLICY="{
  \"Version\":\"2012-10-17\",
  \"Statement\":[
    {
      \"Effect\":\"Allow\",
      \"Action\":[\"ecs:UpdateService\",\"ecs:DescribeServices\"],
      \"Resource\":\"arn:aws:ecs:${REGION}:${ACCOUNT}:service/${CLUSTER}/*\"
    },
    {
      \"Effect\":\"Allow\",
      \"Action\":\"ecs:ListServices\",
      \"Resource\":\"*\"
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

ENV_VARS="Variables={CLUSTER=$CLUSTER,SERVICE_PREFIXES=$FALLBACK_PREFIXES}"

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
      --timeout 60 \
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

# ── 4. Cleanup of legacy schedule names ───────────────────────────────────────
# Earlier versions of this script used unsuffixed names. Remove them so the
# only live schedules are the monthly-/daily- pair below.
for OLD in financeagent-scale-up financeagent-scale-down; do
  if aws scheduler get-schedule --name "$OLD" --region "$REGION" &>/dev/null; then
    aws scheduler delete-schedule --name "$OLD" --region "$REGION" > /dev/null
    echo "  Deleted legacy schedule: $OLD"
  fi
done

# ── 5. Schedules ──────────────────────────────────────────────────────────────
echo "── Schedules ──"

upsert_schedule() {
  local NAME="$1"
  local CRON="$2"
  local INPUT_JSON="$3"

  local TARGET="{\"Arn\":\"${LAMBDA_ARN}\",\"RoleArn\":\"${SCHEDULER_ROLE_ARN}\",\"Input\":${INPUT_JSON}}"
  local CMD="create-schedule"
  if aws scheduler get-schedule --name "$NAME" --region "$REGION" &>/dev/null; then
    CMD="update-schedule"
  fi

  aws scheduler "$CMD" \
    --name "$NAME" \
    --schedule-expression "$CRON" \
    --schedule-expression-timezone "$TIMEZONE" \
    --flexible-time-window "Mode=OFF" \
    --target "$TARGET" \
    --region "$REGION" --no-cli-pager > /dev/null
  echo "  $CMD: $NAME ($CRON $TIMEZONE)"
}

# INPUT must be a JSON STRING containing JSON (double-encoded for the API).
# Build the per-cadence payloads with prefixes embedded so the Lambda routes
# to the correct service set regardless of which schedule fires.
MONTHLY_UP_INPUT="\"{\\\"action\\\":\\\"up\\\",\\\"prefixes\\\":\\\"${MONTHLY_PREFIXES}\\\"}\""
MONTHLY_DOWN_INPUT="\"{\\\"action\\\":\\\"down\\\",\\\"prefixes\\\":\\\"${MONTHLY_PREFIXES}\\\"}\""
DAILY_UP_INPUT="\"{\\\"action\\\":\\\"up\\\",\\\"prefixes\\\":\\\"${DAILY_PREFIXES}\\\"}\""
DAILY_DOWN_INPUT="\"{\\\"action\\\":\\\"down\\\",\\\"prefixes\\\":\\\"${DAILY_PREFIXES}\\\"}\""

upsert_schedule "financeagent-monthly-scale-up"   "cron(0 0 L-2 * ? *)" "$MONTHLY_UP_INPUT"
upsert_schedule "financeagent-monthly-scale-down" "cron(0 0 9 * ? *)"   "$MONTHLY_DOWN_INPUT"
upsert_schedule "financeagent-daily-scale-up"     "cron(0 ${DAILY_UP_HOUR} * * ? *)"   "$DAILY_UP_INPUT"
upsert_schedule "financeagent-daily-scale-down"   "cron(0 ${DAILY_DOWN_HOUR} * * ? *)" "$DAILY_DOWN_INPUT"

echo ""
echo "=== Setup complete ==="
echo "Monthly cadence: L-2 of prior month 00:00 → day 9 00:00 $TIMEZONE"
echo "  Prefixes: $MONTHLY_PREFIXES"
echo "Daily cadence: ${DAILY_UP_HOUR}:00 → ${DAILY_DOWN_HOUR}:00 $TIMEZONE"
echo "  Prefixes: $DAILY_PREFIXES"
echo ""
echo "Smoke test (scale UP the monthly set now):"
echo "  aws lambda invoke --function-name $LAMBDA_NAME \\"
echo "    --payload '{\"action\":\"up\",\"prefixes\":\"$MONTHLY_PREFIXES\"}' \\"
echo "    --cli-binary-format raw-in-base64-out /tmp/scale.json && cat /tmp/scale.json"
echo ""
echo "Smoke test (scale UP the daily set now):"
echo "  aws lambda invoke --function-name $LAMBDA_NAME \\"
echo "    --payload '{\"action\":\"up\",\"prefixes\":\"$DAILY_PREFIXES\"}' \\"
echo "    --cli-binary-format raw-in-base64-out /tmp/scale.json && cat /tmp/scale.json"
echo ""
echo "Disable a schedule (e.g. for an off-cycle freeze):"
echo "  aws scheduler get-schedule --name financeagent-monthly-scale-up --region $REGION \\"
echo "    | jq '.State = \"DISABLED\"' \\"
echo "    | aws scheduler update-schedule --cli-input-json file:///dev/stdin --region $REGION"
