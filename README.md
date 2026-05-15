# Financial Agentic Framework

Automated payroll submission framework for Costa Rican government portals (CCSS Sicere, INS RT-Virtual, Hacienda OVI). A Playwright-based agent worker receives payroll envelopes from Praxis BPM via AWS SQS, logs in to each portal, applies salary data, and returns a structured result for BPMN correlation.

## Module overview

| Module | Role |
|---|---|
| `common-lib` | Shared records, `BigDecimal` serializers, payload comparators |
| `contract-api` | JSON Schema files (`schemas/v1/*.json`), envelope POJOs, `SchemaValidator` — **published to GitHub Packages; consumed by Praxis** |
| `agent-worker` | Spring Boot app; `@SqsListener` per portal, Playwright adapters, idempotency store |
| `agent-gateway` | HTTP gateway (future use) |
| `mcp-payroll-server` | MCP-compatible payroll server |
| `testing-harness` | Mock government portal at `http://localhost:3000` for local E2E testing |

## Prerequisites

- Java 21 (Temurin)
- Maven 3.9+ (`mvn.cmd` on PATH — no wrapper in this project)
- Docker Desktop (for LocalStack and Playwright Testcontainers in CI)
- AWS CLI configured with `PraxisDeveloperLocal` policy for dev-queue runs

## Building

```powershell
# Build and test all modules
mvn.cmd clean verify

# Build a single module (and its dependencies)
mvn.cmd clean verify -pl agent-worker -am
```

Unit tests run under `surefire`; integration tests (`*IT.java`) run under `failsafe` via `mvn verify`. Integration tests require Docker Desktop and fail on Windows if the Docker named pipe is unavailable.

## Running locally

### Mock-payroll server (testing-harness)

Start before any mock-payroll capture/submit flow:

```powershell
Start-Process -FilePath "mvn.cmd" -ArgumentList "spring-boot:run -pl testing-harness" `
  -WorkingDirectory "E:\Work\2026\FinanceAgentFramework" `
  -WindowStyle Hidden `
  -RedirectStandardOutput "logs\harness.log" `
  -RedirectStandardError "logs\harness-err.log"
```

Credentials: `admin` / `password`. Reset roster between runs: `curl -X POST http://localhost:3000/employees/reset`.

### Agent-worker against real AWS dev queues

```powershell
mvn.cmd spring-boot:run -pl agent-worker `
  "-Dspring-boot.run.jvmArguments=-DAWS_ACCESS_KEY_ID=... -DAWS_SECRET_ACCESS_KEY=... -DAWS_REGION=us-east-1 -DPORTAL_ID=mock-payroll"
```

See [docs/LocalWorkerRunbook.md](docs/LocalWorkerRunbook.md) for the full runbook, including queue-prefix configuration and secrets path conventions.

## GitHub Packages — consuming contract-api

Praxis BPM and any other consumer must add the GitHub Packages repository to their `pom.xml` or `settings.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/hzunigav/FinancialAgenticFramework</url>
  </repository>
</repositories>
```

Then depend on:

```xml
<dependency>
  <groupId>com.neoproc.financialagent</groupId>
  <artifactId>contract-api</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Authentication requires a GitHub personal access token with `read:packages` scope in `~/.m2/settings.xml`.

## GitHub Packages — publishing

Three artifacts are published automatically: `finance-agent-root`, `common-lib`, `contract-api`.

**Trigger:** push to `main` when `contract-api/**` or `pom.xml` changes → `.github/workflows/publish-contract-api.yml` runs `mvn -pl contract-api -am deploy`.

**When a push is required** (= when Praxis needs updated JARs):
- Any `contract-api/src/main/resources/schemas/v1/*.json` change (schema additions, new enum values, field renames)
- Any `contract-api/src/main/java/**` change (Java API classes)
- Any `common-lib/src/main/java/**` change
- Root `pom.xml` dependency version bumps

Changes confined to `agent-worker/`, `agent-gateway/`, `testing-harness/`, or `mcp-payroll-server/` do **not** require a republish.

## Key documentation

| Document | What it covers |
|---|---|
| [docs/ImplementationPlan.md](docs/ImplementationPlan.md) | Phase roadmap and code milestones |
| [docs/DeploymentPlan.md](docs/DeploymentPlan.md) | Prod AWS provisioning, Fargate deployment, Praxis cutover |
| [docs/SqsMigrationPlan.md](docs/SqsMigrationPlan.md) | SQS transport design, queue naming, contract addendum, §10 tracking checklist |
| [docs/LocalWorkerRunbook.md](docs/LocalWorkerRunbook.md) | How to start the agent-worker locally against real dev queues |
| [docs/WorkerActionTypes.md](docs/WorkerActionTypes.md) | Canonical taxonomy of portal action types |
| [docs/EnhancementsBacklog.md](docs/EnhancementsBacklog.md) | Deferred items (encryption, IaC, persistent idempotency store) |
| [contract-api/CONTRACT.md](contract-api/CONTRACT.md) | Wire-format contract for envelope schemas |
