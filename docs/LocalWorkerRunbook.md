# Local Worker Runbook

How to start an `agent-worker` on your dev machine and have it drain a real
queued task from local RabbitMQ. Use this when:

- A test envelope is sitting on `financeagent.tasks.{capture,submit}.<portalId>`
  and you want to watch it run end-to-end.
- You just changed adapter / engine / descriptor code and want a real-portal
  iteration before packaging the Docker image.

For descriptor authoring iteration (no broker, single-shot), see
[PortalOnboarding.md](PortalOnboarding.md). For production rollout via Fargate,
see [PortalDeployment.md](PortalDeployment.md). This doc is the in-between:
**broker in the loop, browser on your screen, code from your workspace.**

---

## 1. Prerequisites

- **RabbitMQ** reachable on `localhost:5672` (mgmt UI on `localhost:15672`,
  guest/guest). Praxis's compose stack supplies it; `docker ps` should show
  `praxis-rabbitmq-1` healthy.
- **Local secrets** at `~/.financeagent/secrets.properties` containing
  `portals.<portalId>.credentials.<key>=<value>` for every credential the
  descriptor references via `${credentials.<key>}`. See `secrets.properties.example`.
- **Playwright Chromium** in the local cache. Already populated if you've run
  any portal locally before; otherwise the first run will download it
  (`%USERPROFILE%/AppData/Local/ms-playwright/chromium-1140`).
- **Worker JAR** built. Either the previous `mvn package` is fresh, or you run
  the build below.

If something on `localhost:8080` is *not* the worker (e.g. another dev app),
you'll get `Port 8080 was already in use` on startup — pick a free port via
`SERVER_PORT=8081`. Spring Boot Actuator's `/actuator/health` and
`/actuator/prometheus` follow the chosen port.

---

## 2. Build the worker

Run from repo root. Skips tests for speed; if you want the safety net, drop
`-DskipTests`.

```bash
mvn -pl agent-worker -am package -DskipTests -q
```

Output: `agent-worker/target/agent-worker-1.0.0-SNAPSHOT.jar` (~210 MB fat
JAR; bundles Playwright, Spring Boot, contract-api, common-lib).

Skip this step if no Java code under `agent-worker/`, `common-lib/`, or
`contract-api/` has changed since the last build.

---

## 3. Start the worker

One worker process binds to one `PORTAL_ID`. The same process consumes that
portal's `capture.<id>` *and* `submit.<id>` queues — no need to start two.

**Required env vars:**

| Var                         | Local value          | Why                                                   |
| --------------------------- | -------------------- | ----------------------------------------------------- |
| `PORTAL_ID`                 | e.g. `autoplanilla`  | Selects descriptor + queue bindings.                  |
| `RABBITMQ_HOST`             | `localhost`          | Praxis stack publishes to local broker.               |
| `RABBITMQ_PORT`             | `5672`               | Default AMQP port.                                    |
| `RABBITMQ_USERNAME`         | `guest`              | Default mgmt creds in compose.                        |
| `RABBITMQ_PASSWORD`         | `guest`              |                                                       |
| `FINANCEAGENT_CIPHER`       | `cleartext` (or unset) | No envelope encryption on the wire. Required when Praxis is the consumer — Praxis only handles cleartext or `kms-envelope-v1` on inbound results (PraxisIntegrationHandoff §A.3). The `=local` value is a worker-only at-rest scheme; **don't use it when Praxis is in the loop** — it produces `local-aes-gcm-v1` ciphertext Praxis cannot read, and the workflow stalls silently. |
| `FINANCEAGENT_CREDENTIALS`  | `local`              | Read creds from `~/.financeagent/secrets.properties` instead of AWS Secrets Manager. |
| `SERVER_PORT` *(optional)*  | `8081` if 8080 busy  | Spring Boot HTTP port for `/actuator/*`.              |

**Useful system properties (`-D...`):**

- `-Dportal.headless=false` — opens a visible Chromium window so you can watch
  the run. Default `true` (headless) for containerised / CI use.
  Equivalently: env `PORTAL_HEADLESS=false`.
- `-Djava.awt.headless=true` — keep Java AWT off; Chromium does the rendering.

**Invocation (autoplanilla, visible browser, port 8081):**

```bash
PORTAL_ID=autoplanilla \
SERVER_PORT=8081 \
RABBITMQ_HOST=localhost RABBITMQ_PORT=5672 \
RABBITMQ_USERNAME=guest RABBITMQ_PASSWORD=guest \
FINANCEAGENT_CIPHER=cleartext FINANCEAGENT_CREDENTIALS=local \
java -Dportal.headless=false -Djava.awt.headless=true \
  -jar agent-worker/target/agent-worker-1.0.0-SNAPSHOT.jar \
  > logs/agent-worker-autoplanilla.log 2>&1 &
```

Same template for any other portal — swap `PORTAL_ID`. The worker self-declares
its queues, exchanges, and DLX on startup (idempotent — safe against an
already-populated broker).

---

## 4. Watch it work

Three signals worth watching:

**Worker log** (most informative):

```bash
tail -F logs/agent-worker-autoplanilla.log | \
  grep -E "receive portalId|run started|step=|capture|result published|run failed|ERROR|Exception"
```

Healthy run prints, in order:

```
Started WorkerApplication ...
receive portalId=<id> envelopeId=<uuid>
run started portal=<id> shadowMode=<bool> sessionReused=<bool>
... (one log line per descriptor step) ...
result published envelopeId=<uuid> resultStatus=SUCCESS
```

**RabbitMQ queue depth** (drops as messages drain):

```bash
curl -s -u guest:guest \
  'http://localhost:15672/api/queues/%2F/financeagent.tasks.capture.<portalId>' | \
  jq '{messages, consumers}'
```

`consumers` should be `1` while the worker is up; `messages` should reach `0`
after each run finishes. Results land on `financeagent.results` (Praxis
consumes it from there).

**Run artifacts** (one directory per run, sorted by start time):

```bash
ls -t agent-worker/artifacts | head -5
```

Each run dir contains `manifest.json` (step-by-step trace), `network.har`,
`trace.zip` (open in Playwright Trace Viewer), `report.png`, and the typed
envelope (`payroll-{capture,submit}-{request,result}.v1.json`).

---

## 5. Stop it

If foregrounded: `Ctrl+C`.

If backgrounded with `&` or via your tool of choice:

```bash
# Find by listening port
lsof -i :8081  # or netstat -ano | grep 8081 on Windows
kill <pid>
```

The worker drains the in-flight message (Spring AMQP `acknowledge-mode: auto`
acks on listener return) and exits; queued messages stay queued for the next
start.

---

## 6. Common failure modes

| Symptom                                                     | Cause                                                                 | Fix                                                                                       |
| ----------------------------------------------------------- | --------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `Port 8080 was already in use` then exit 1                  | Another local app holds 8080.                                         | Add `SERVER_PORT=8081` (or any free port).                                                |
| `Connection refused` to `localhost:5672`                    | Praxis compose stack not running.                                     | `docker compose -f <praxis-stack>/docker-compose.yml up -d` then re-check `docker ps`.    |
| Worker starts, queue still has messages, `consumers=0`      | Wrong `PORTAL_ID` (queue is `capture.<id>` but you started a different `<id>`). | Restart with the matching `PORTAL_ID`.                                                  |
| `Missing credential 'username' for portal '<id>'`           | No matching `portals.<id>.credentials.username=...` line.             | Add it to `~/.financeagent/secrets.properties`.                                           |
| Run fails at a `waitForSelector` step                       | Portal HTML changed (MUI/css-hash drift, redesign).                   | Re-record selectors (Playwright codegen) and update the descriptor under `resources/portals/`. |
| Schema validation error on outgoing result                  | Adapter built an envelope that violates the published JSON schema.    | See `feedback_schema_validation` memory: update the schema in the same PR as the code change, then rebuild. |
| `IllegalStateException: Unresolved portal binding: params.<name>` | Descriptor or adapter references a binding the listener doesn't emit. | Rename to a listener-supplied binding (`params.from`, `params.to`, `params.planillaId`, `params.planillaName`, `params.firmId`, etc.) — see [PortalOnboarding.md "Descriptor design pitfalls"](PortalOnboarding.md#descriptor-design-pitfalls). |
| `select` step times out with `did not find some options`    | Selecting by visible label, but the option text was renamed or never matched. | Switch the descriptor's `select` step to `match: value` and bind to the underlying `<option value="...">` (typically a stable id from the envelope). |
| Post-login `waitForSelector` times out, only when `sessionReused=true` in the manifest | Portal stores auth in `sessionStorage` / in-memory JWT; `storageState()` captures nothing useful. | Set `session.ttlMinutes: 0` in the descriptor; delete `~/.financeagent/sessions/<portalId>.enc`; rebuild and restart. |
| Selector / binding change in YAML didn't take effect after restart | Descriptor YAML lives under `agent-worker/src/main/resources/portals/` and is bundled into the fat JAR at build time. | Rebuild before restarting: `mvn -pl agent-worker -am package -DskipTests -q`. |
| Praxis workflow stuck on "Wait for capture/submit result" but worker logged `result published` | Wire-cipher mismatch: worker is emitting `local-aes-gcm-v1` ciphertext that Praxis cannot decrypt and silently drops. | Set `FINANCEAGENT_CIPHER=cleartext` (or unset, which now defaults to cleartext). `local` is a worker-only at-rest scheme — never use it when Praxis is the consumer. See [docs/PraxisOpenIssues.md OI-001](PraxisOpenIssues.md). |

---

## 7. Why a runbook instead of a script

A wrapper script would hide which env vars matter and bit-rot the moment a
portal added a new variable. The shape of "what to start a worker for a portal"
*is* the contract — a table you can re-derive from. If you find yourself
running the same command three times in one afternoon, drop a `.envrc` /
`direnv` snippet locally; don't add it to the repo.
