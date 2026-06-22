"""
scaling-lambda — flips the desired-count of one or more financeagent ECS
services between 0 and 1 to implement calendar-based scale-to-zero.

Triggered by EventBridge Scheduler rules (see setup-scaling-schedule.sh).
Two cadences are wired by default:

  Monthly (payroll workers + testing-harness):
    financeagent-monthly-scale-up   — L-2 of prior month 00:00 CR
    financeagent-monthly-scale-down — day 9 of new month 00:00 CR

  Daily (bank-statement portals such as Xero):
    financeagent-daily-scale-up   — every day at DAILY_UP_HOUR CR
    financeagent-daily-scale-down — every day at DAILY_DOWN_HOUR CR

Event payload:
  {"action": "up"|"down", "prefixes": "<csv-of-prefixes>"}

  The `prefixes` field is normally supplied by the schedule and determines
  which services this firing targets. For ad-hoc invocations, the
  SERVICE_PREFIXES env var is used as a fallback if the event omits it.

Env vars:
  CLUSTER          ECS cluster name (e.g. financeagent)
  SERVICE_PREFIXES Fallback prefixes for ad-hoc invocations only — schedules
                   pass their own prefixes per-event so this env can be
                   absent in normal operation.

Prefix semantics: a value ending in "-" matches any service whose name
starts with it (prefix); a bare name matches only on equality. So
"financeagent-worker-,testing-harness" matches every payroll worker plus
the exact name "testing-harness".

Idempotent: re-firing the same action is a no-op (ECS UpdateService accepts
the same desiredCount silently).
"""
import json
import os

import boto3
from botocore.exceptions import ClientError

ecs = boto3.client("ecs")
CLUSTER = os.environ["CLUSTER"]


def _resolve_prefixes(event):
    """Prefer event.prefixes (scheduled invocation); fall back to env (ad-hoc)."""
    raw = event.get("prefixes") or os.environ.get("SERVICE_PREFIXES", "")
    prefixes = [p.strip() for p in raw.split(",") if p.strip()]
    if not prefixes:
        raise ValueError(
            "No prefixes — pass event.prefixes or set SERVICE_PREFIXES env"
        )
    return prefixes


def _discover_services(prefixes):
    """List active services in the cluster matching any prefix."""
    matched = []
    paginator = ecs.get_paginator("list_services")
    for page in paginator.paginate(cluster=CLUSTER, maxResults=100):
        for arn in page["serviceArns"]:
            # ARN tail: arn:aws:ecs:region:account:service/cluster/<service-name>
            name = arn.rsplit("/", 1)[-1]
            if any(name == p or (p.endswith("-") and name.startswith(p)) for p in prefixes):
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

    prefixes = _resolve_prefixes(event)
    services = _discover_services(prefixes)
    if not services:
        msg = f"No services matched prefixes {prefixes} in cluster {CLUSTER}"
        print(json.dumps({"action": action, "prefixes": prefixes, "warning": msg}))
        return {"action": action, "prefixes": prefixes, "warning": msg}

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

    print(json.dumps({"action": action, "prefixes": prefixes, "results": results}))
    return {"action": action, "prefixes": prefixes, "results": results}
