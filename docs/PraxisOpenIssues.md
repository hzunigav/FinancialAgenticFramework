# Praxis Open Issues

Open contract divergences and bugs that need a Praxis-side fix. Each entry is
self-contained so it can be lifted into a Linear/Jira ticket as-is.

---

## OI-001 — BPMN result listener consumes from off-spec queue (workflow stalls forever)

**Status:** open, blocking end-to-end Payroll Cycle runs.

**Found:** 2026-04-28 during local smoke test of the AutoPlanilla capture
flow against this Praxis instance.

### Symptom

A `Payroll Cycle` workflow instance stays on **"Wait for capture result"**
indefinitely after the worker publishes a successful `payroll-capture-result.v1`
envelope. No errors surface anywhere — no DLQ activity, no Praxis exception
log, no worker error. The result queue shows zero messages because something
is draining and acking them, but the BPMN engine never advances.

Reproduced on workflow instances **#3604** and **#3605** with two independently
correct capture results.

### Evidence

RabbitMQ queue stats (vhost `/`) at the time of the stall:

| Queue                       | publish | deliver | ack | consumers |
| --------------------------- | ------- | ------- | --- | --------- |
| `financeagent.results`      | 7       | 7       | 7   | 1         |
| `praxis.agent.result.queue` | 0       | 0       | 0   | 1         |

Bindings:

```
financeagent.results (exchange, direct)
  └─ financeagent.results (queue, routing-key: "")        ← worker publishes here

praxis.agent.result (exchange, declared by Praxis)
  └─ praxis.agent.result.queue (routing-key: "agent.result")  ← Praxis BPMN listens here
```

Both consumers come from the same Praxis container connection
(`172.20.0.1:50508`). The consumer on `financeagent.results` is acking
messages but does not advance the BPMN process — it appears to be a no-op
drain or a leftover bridge. The consumer on `praxis.agent.result.queue`
is the BPMN result handler, but no messages ever arrive there because
no producer is bound to publish onto the `praxis.agent.result` exchange.

### Contract reference

[PraxisIntegrationHandoff.md §C.1](PraxisIntegrationHandoff.md#c1-broker--topology) specifies the topology:

> ```
> Exchange: financeagent.results           (direct)
> Queues:
>   financeagent.results                     (shared results queue, Praxis consumes)
> ```

[§C.2](PraxisIntegrationHandoff.md#c2-message-envelope) specifies the AMQP
properties on the inbound request, including `reply-to: financeagent.results`.

[§C.3 step 5](PraxisIntegrationHandoff.md#c3-worker-runtime-change):

> Publish `PayrollSubmitResult` to `financeagent.results` with `correlation-id`
> set to the incoming envelope's `businessKey`.

[§C.3 acceptance](PraxisIntegrationHandoff.md#c3-worker-runtime-change):

> Praxis publishes a `payroll-submit-request.v1` for mock-payroll, the worker
> processes it, and Praxis observes a `payroll-submit-result.v1` on
> `financeagent.results` with matching correlation-id.

The contract names `financeagent.results` (queue) as the shared consumption
point in three independent places. The worker is publishing exactly there.
Praxis has introduced a non-spec exchange (`praxis.agent.result`) and queue
(`praxis.agent.result.queue`) that are not in the handoff and that the worker
side has no contract to publish to.

### Fix (Praxis-side; pick one)

**Option A (preferred — strict spec compliance):** move the BPMN result
listener to consume directly from queue `financeagent.results`. Remove the
off-spec `praxis.agent.result` exchange and `praxis.agent.result.queue`.

**Option B (compatible with existing internal naming):** keep
`praxis.agent.result.queue` as an internal implementation detail of the
listener, but bind it to the spec exchange `financeagent.results` with
routing key `""`:

```
praxis.agent.result.queue
  ├─ binding to "" (default)              with routing-key: praxis.agent.result.queue
  ├─ binding to praxis.agent.result        with routing-key: agent.result   (off-spec; can stay or be removed)
  └─ binding to financeagent.results       with routing-key: ""             ← ADD THIS
```

Either way, also remove (or fix the wiring of) whatever consumer is currently
acking messages on the `financeagent.results` queue without routing them into
the BPMN engine — that's the silent failure mode that hid this for a day.

### Acceptance test

After the fix, the §C.3 acceptance test from the handoff should pass: a
`payroll-submit-request.v1` published by Praxis for `mock-payroll` results in
the agent-worker emitting a `payroll-submit-result.v1`, and the corresponding
Receive Task in the BPMN workflow advances within seconds.

### Workaround until fixed

Add the §C.1-compliant binding from `financeagent.results` exchange to
`praxis.agent.result.queue` via the RabbitMQ Management UI:

```
PUT /api/bindings/%2F/e/financeagent.results/q/praxis.agent.result.queue
{ "routing_key": "", "arguments": {} }
```

This is a runtime-only broker state change (will be lost if the broker
container is recreated without persistent volumes). It is **not** a
substitute for the Praxis-side fix.
