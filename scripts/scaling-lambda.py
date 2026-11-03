"""
scaling-lambda — demand-driven scale-to-zero for the financeagent ECS services.

Runs once a minute (EventBridge Scheduler, rate(1 minute); see
setup-scaling-schedule.sh). Each tick, for every service in the cluster that
opts in, it reads the depth of the queues that service consumes and sets the
desired count to 1 when there is work, 0 once it has been idle for a while.

This replaces the earlier calendar windows (monthly payroll window, daily Xero
window). Those never matched the traffic: bots like SICERE reports and INS
altas get work on any day, and a window that is open 11 days a month still
bills 11 days for what is, in practice, a couple of hours of runs.

Opt-in, per service, via an ECS service tag:

    financeagent:task-queues = <queue-name> [<queue-name> ...]   (space-separated;
                                                                   ECS tag values
                                                                   can't hold commas)

List EXACTLY the queues the worker consumes. A listed queue the worker does not
poll would hold the service at 1 forever, because nothing would ever drain it.
Services without the tag are left alone.

Decision per service:

    Visible > 0 on any queue      -> desired 1  (work waiting)
    NotVisible > 0 on any queue   -> hold       (a run is in progress, or a crashed
                                                 run's message is waiting out its
                                                 visibility timeout)
    otherwise                     -> idle: after IDLE_MINUTES of consecutive idle
                                     ticks, desired 0

Idle-since is kept on the service itself (tag financeagent:idle-since, epoch
seconds), so the Lambda stays stateless.

Two layers keep a scale-in from interrupting a run: an in-flight message
counts as NotVisible, so the service is held; and the worker holds ECS task
scale-in protection while it processes (TaskProtectionInterceptor), so even a
desired-count drop racing a just-received message leaves the task running
until the run finishes.

Queue depth is read with GetQueueAttributes rather than CloudWatch metrics:
SQS metrics for a queue that has been inactive can lag by up to 15 minutes,
which would add that much to every cold start.

Env vars:
  CLUSTER        ECS cluster name (required)
  IDLE_MINUTES   idle time before scaling to 0 (default 5)
  DRY_RUN        "true" -> log decisions without changing any desired count
                 (the idle-since tag is still kept, so scale-downs show up)
"""
import json
import os
import time

import boto3
from botocore.exceptions import ClientError

ecs = boto3.client("ecs")
sqs = boto3.client("sqs")

CLUSTER = os.environ["CLUSTER"]
IDLE_MINUTES = int(os.environ.get("IDLE_MINUTES", "5"))
DRY_RUN = os.environ.get("DRY_RUN", "").lower() == "true"

QUEUES_TAG = "financeagent:task-queues"
IDLE_TAG = "financeagent:idle-since"

# Queue URLs never change; keep them across warm invocations.
_queue_urls = {}


def _queue_url(name):
    if name not in _queue_urls:
        _queue_urls[name] = sqs.get_queue_url(QueueName=name)["QueueUrl"]
    return _queue_urls[name]


def _depth(queue_names):
    """Sum Visible and NotVisible across the service's queues."""
    visible = in_flight = 0
    for name in queue_names:
        attrs = sqs.get_queue_attributes(
            QueueUrl=_queue_url(name),
            AttributeNames=[
                "ApproximateNumberOfMessages",
                "ApproximateNumberOfMessagesNotVisible",
            ],
        )["Attributes"]
        visible += int(attrs["ApproximateNumberOfMessages"])
        in_flight += int(attrs["ApproximateNumberOfMessagesNotVisible"])
    return visible, in_flight


def _opted_in_services():
    """Yield (service, tags) for every service carrying the queues tag."""
    arns = []
    for page in ecs.get_paginator("list_services").paginate(cluster=CLUSTER, maxResults=100):
        arns.extend(page["serviceArns"])
    for i in range(0, len(arns), 10):  # DescribeServices takes at most 10
        resp = ecs.describe_services(cluster=CLUSTER, services=arns[i:i + 10], include=["TAGS"])
        for svc in resp["services"]:
            if svc.get("status") != "ACTIVE":
                continue
            tags = {t["key"]: t["value"] for t in svc.get("tags", [])}
            if tags.get(QUEUES_TAG, "").strip():
                yield svc, tags


def decide(desired, visible, in_flight, idle_since, now, idle_minutes=IDLE_MINUTES):
    """
    Pure decision function — returns (new_desired, new_idle_since).
    new_idle_since: None = clear the tag, int = set it, unchanged = leave it.
    """
    if visible > 0:
        return 1, None
    if in_flight > 0:
        return desired, None
    if desired == 0:
        return 0, None
    if idle_since is None:
        return desired, now
    if now - idle_since >= idle_minutes * 60:
        return 0, None
    return desired, idle_since


def _apply(svc, tags, now):
    name = svc["serviceName"]
    queues = tags[QUEUES_TAG].split()
    desired = svc["desiredCount"]
    idle_raw = tags.get(IDLE_TAG)
    idle_since = int(idle_raw) if idle_raw and idle_raw.isdigit() else None

    visible, in_flight = _depth(queues)
    new_desired, new_idle = decide(desired, visible, in_flight, idle_since, now)

    result = {
        "service": name, "visible": visible, "inFlight": in_flight,
        "desired": desired, "newDesired": new_desired,
    }
    if DRY_RUN:
        result["dryRun"] = True
    elif new_desired != desired:
        ecs.update_service(cluster=CLUSTER, service=name, desiredCount=new_desired)

    # The idle stamp is kept even in a dry run. It is bookkeeping, not a
    # scaling action, and without it the idle clock never starts, so a dry run
    # could never reach — or show — a scale-to-zero decision.
    if new_idle is None and idle_since is not None:
        ecs.untag_resource(resourceArn=svc["serviceArn"], tagKeys=[IDLE_TAG])
    elif new_idle is not None and new_idle != idle_since:
        ecs.tag_resource(resourceArn=svc["serviceArn"], tags=[{"key": IDLE_TAG, "value": str(new_idle)}])
    return result


def lambda_handler(_event, _context):
    now = int(time.time())
    results = []
    for svc, tags in _opted_in_services():
        try:
            results.append(_apply(svc, tags, now))
        except ClientError as e:
            # One bad service (missing queue, throttle) must not stall the rest.
            results.append({"service": svc["serviceName"], "status": "error", "error": str(e)})

    # Log only ticks that changed something or failed — 1,440 ticks a day of
    # "nothing to do" would bury the interesting lines.
    changed = [r for r in results if r.get("status") == "error" or r.get("newDesired") != r.get("desired")]
    if changed:
        print(json.dumps({"changes": changed}))
    return {"services": len(results), "changes": changed}
