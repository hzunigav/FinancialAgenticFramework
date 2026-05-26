#!/usr/bin/env bash
# github-oidc-setup.sh — one-time setup of the GitHub Actions → AWS trust.
#
# Run from AWS CloudShell (once per AWS account).
# After this runs, set the AWS_DEPLOY_ROLE_ARN secret in the GitHub repository:
#   GitHub → Settings → Secrets and variables → Actions → New repository secret
#
# Usage:
#   ./scripts/github-oidc-setup.sh <github-org>/<github-repo>
#
# Example:
#   ./scripts/github-oidc-setup.sh neoproc/FinanceAgentFramework
#
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <github-org>/<github-repo>" >&2
  exit 1
fi

REPO="$1"         # e.g. neoproc/FinanceAgentFramework
REGION="${REGION:-us-east-1}"
ACCOUNT="${ACCOUNT:-409159414704}"
ROLE_NAME="github-actions-financeagent-deploy"
POLICY_NAME="GitHubActionsFinanceagentDeploy"
OIDC_URL="https://token.actions.githubusercontent.com"
OIDC_THUMBPRINT="6938fd4d98bab03faadb97b34396831e3780aea1"

echo "=== Step 1: Create GitHub OIDC provider (skip if already exists) ==="
EXISTING=$(aws iam list-open-id-connect-providers \
  --query "OpenIDConnectProviderList[?ends_with(Arn, 'token.actions.githubusercontent.com')].Arn" \
  --output text)

if [[ -n "$EXISTING" ]]; then
  OIDC_ARN="$EXISTING"
  echo "  OIDC provider already exists: $OIDC_ARN"
else
  OIDC_ARN=$(aws iam create-open-id-connect-provider \
    --url "$OIDC_URL" \
    --client-id-list "sts.amazonaws.com" \
    --thumbprint-list "$OIDC_THUMBPRINT" \
    --query 'OpenIDConnectProviderArn' --output text)
  echo "  Created OIDC provider: $OIDC_ARN"
fi

echo ""
echo "=== Step 2: Create IAM role with trust policy for this repository ==="

TRUST_POLICY=$(jq -n \
  --arg oidc_arn "$OIDC_ARN" \
  --arg repo     "$REPO" \
'{
  Version: "2012-10-17",
  Statement: [{
    Effect:    "Allow",
    Principal: { Federated: $oidc_arn },
    Action:    "sts:AssumeRoleWithWebIdentity",
    Condition: {
      StringEquals: {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
      },
      StringLike: {
        "token.actions.githubusercontent.com:sub": ("repo:" + $repo + ":*")
      }
    }
  }]
}')

EXISTING_ROLE=$(aws iam get-role --role-name "$ROLE_NAME" \
  --query 'Role.Arn' --output text 2>/dev/null || echo "")

if [[ -n "$EXISTING_ROLE" ]]; then
  ROLE_ARN="$EXISTING_ROLE"
  echo "  Role already exists: $ROLE_ARN"
  # Update trust policy in case the repo name changed
  aws iam update-assume-role-policy \
    --role-name "$ROLE_NAME" \
    --policy-document "$TRUST_POLICY"
  echo "  Trust policy updated"
else
  ROLE_ARN=$(aws iam create-role \
    --role-name "$ROLE_NAME" \
    --assume-role-policy-document "$TRUST_POLICY" \
    --description "Assumed by GitHub Actions for financeagent ECR+ECS deployments" \
    --query 'Role.Arn' --output text)
  echo "  Created role: $ROLE_ARN"
fi

echo ""
echo "=== Step 3: Create and attach deployment permissions policy ==="

DEPLOY_POLICY=$(jq -n \
  --arg account "$ACCOUNT" \
  --arg region  "$REGION" \
'{
  Version: "2012-10-17",
  Statement: [
    {
      Sid:      "ECRAuth",
      Effect:   "Allow",
      Action:   "ecr:GetAuthorizationToken",
      Resource: "*"
    },
    {
      Sid:    "ECRPush",
      Effect: "Allow",
      Action: [
        "ecr:BatchCheckLayerAvailability",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:PutImage",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage",
        "ecr:CreateRepository",
        "ecr:DescribeRepositories"
      ],
      Resource: ("arn:aws:ecr:" + $region + ":" + $account + ":repository/financeagent-*")
    },
    {
      Sid:    "ECSDescribeAndDeploy",
      Effect: "Allow",
      Action: [
        "ecs:DescribeServices",
        "ecs:DescribeTaskDefinition",
        "ecs:RegisterTaskDefinition",
        "ecs:UpdateService"
      ],
      Resource: "*"
    },
    {
      Sid:    "PassExecutionRole",
      Effect: "Allow",
      Action: "iam:PassRole",
      Resource: ("arn:aws:iam::" + $account + ":role/financeagent-*"),
      Condition: {
        StringEquals: { "iam:PassedToService": "ecs-tasks.amazonaws.com" }
      }
    }
  ]
}')

POLICY_ARN="arn:aws:iam::${ACCOUNT}:policy/${POLICY_NAME}"
EXISTING_POLICY=$(aws iam get-policy --policy-arn "$POLICY_ARN" \
  --query 'Policy.Arn' --output text 2>/dev/null || echo "")

if [[ -n "$EXISTING_POLICY" ]]; then
  echo "  Policy already exists — creating new version"
  # Clean up old non-default versions first (IAM allows max 5)
  aws iam list-policy-versions --policy-arn "$POLICY_ARN" \
    --query 'Versions[?!IsDefaultVersion].VersionId' --output text | \
  tr '\t' '\n' | while read -r VID; do
    [[ -z "$VID" ]] && continue
    aws iam delete-policy-version --policy-arn "$POLICY_ARN" --version-id "$VID"
  done
  aws iam create-policy-version \
    --policy-arn "$POLICY_ARN" \
    --policy-document "$DEPLOY_POLICY" \
    --set-as-default > /dev/null
else
  POLICY_ARN=$(aws iam create-policy \
    --policy-name "$POLICY_NAME" \
    --policy-document "$DEPLOY_POLICY" \
    --query 'Policy.Arn' --output text)
  echo "  Created policy: $POLICY_ARN"
fi

# Attach policy to role
aws iam attach-role-policy --role-name "$ROLE_NAME" --policy-arn "$POLICY_ARN"
echo "  Policy attached to role"

echo ""
echo "======================================================================"
echo "  DONE. Add this secret to the GitHub repository:"
echo ""
echo "  Secret name : AWS_DEPLOY_ROLE_ARN"
echo "  Secret value: $ROLE_ARN"
echo ""
echo "  GitHub → Settings → Secrets and variables → Actions → New repository secret"
echo "======================================================================"
