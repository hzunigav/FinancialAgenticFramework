"""
scaling-lambda — flips the desired-count of every financeagent worker service
between 0 and 1 to implement calendar-based scale-to-zero.

Triggered by two EventBridge Scheduler rules (see setup-scaling-schedule.sh):
  - financeagent-scale-up   fires at midnight CR on L-2 of each month
  - financeagent-scale-down fires at midnight CR on day 9 of each month

Event payload: {"action": "up"} or {"action": "down"}
Env vars:
  CLUSTER          ECS cluster name (e.g. financeagent)
  SERVICE_PREFIXES Comma-separated list of service name prefixes to match
                   (e.g. "financeagent-worker-,testing-harness"). Trailing
                   "-" makes it a prefix; exact name (no "-") matches by equality.

Service discovery is dynamic: new portals provisioned via prod-infra-setup.sh
are picked up automatically on the next schedule firing, as long as their
service name starts with one of the configured prefixes.

Idempotent: re-firing the same action is a no-op (ECS UpdateService accepts
the same desiredCount silently).
"""
import json
import os

import boto3
from botocore.exceptions import ClientError

ecs = boto3.client("ecs")
CLUSTER = os.environ["CLUSTER"]
PREFIXES = [p.strip() for p in os.environ["SERVICE_PREFIXES"].split(",") if p.strip()]


def _discover_services():
    """List active services in the cluster matching any configured prefix."""
    matched = []
    paginator = ecs.get_paginator("list_services")
    for page in paginator.paginate(cluster=CLUSTER, maxResults=100):
        for arn in page["serviceArns"]:
            # ARN tail: arn:aws:ecs:region:account:service/cluster/<service-name>
            name = arn.rsplit("/", 1)[-1]
            if any(name == p or (p.endswith("-") and name.startswith(p)) for p in PREFIXES):
                matched.append(name)
    return matched


def lambda_handler(event, _context):
    action = event.get("action")
    if action == "up":
        desired = 1
    elif action == "down":
        desired = 0
    else:
        raise ValueError(f"event.action must be 'up' or 'down', got {action!r}")

    services = _discover_services()
    if not services:
        msg = f"No services matched prefixes {PREFIXES} in cluster {CLUSTER}"
        print(json.dumps({"action": action, "warning": msg}))
        return {"action": action, "warning": msg}

    results = []
    for svc in services:
        try:
            ecs.update_service(cluster=CLUSTER, service=svc, desiredCount=desired)
            results.append({"service": svc, "desired": desired, "status": "ok"})
        except ClientError as e:
            # Don't abort the batch on a single failure — log and continue so the
            # other services still flip. A failed flip surfaces in CloudWatch.
            results.append({
                "service": svc,
                "desired": desired,
                "status": "error",
                "error": str(e),
            })

    print(json.dumps({"action": action, "results": results}))
    return {"action": action, "results": results}
