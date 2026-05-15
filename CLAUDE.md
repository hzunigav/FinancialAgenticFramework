# FinanceAgentFramework — Claude Code notes

## Maven
No `mvnw.cmd` in this project. Use the system Maven:
```powershell
mvn.cmd <goals> -pl <module>
```

## Starting the testing-harness (mock-payroll server)
The `testing-harness` module is a Spring Boot app that simulates a government payroll portal at `http://localhost:3000`. Start it before running any `mock-payroll` capture/submit flow.

```powershell
# From project root — runs on port 3000, logs to logs\harness.log
Start-Process -FilePath "mvn.cmd" -ArgumentList "spring-boot:run -pl testing-harness" `
  -WorkingDirectory "E:\Work\2026\FinanceAgentFramework" `
  -WindowStyle Hidden `
  -RedirectStandardOutput "logs\harness.log" `
  -RedirectStandardError "logs\harness-err.log"
```

**Credentials:** `admin` / `password` (hardcoded in `SecurityConfig.userDetailsService()`).  
`~/.financeagent/secrets.properties` already has the matching entries for `portals.mock-payroll.credentials.*`.

**Reset roster between runs** (without restarting):
```
curl -X POST http://localhost:3000/employees/reset
```

**What it serves:**
- `GET  /login` → form with `input[name=username]`, `input[name=password]`, `button[type=submit]`
- Successful login → redirect to `/report` (satisfies `waitForUrl("**/report")`)
- `GET  /employees` → `tr.employee-row` table with `.id-cell`, `.name-cell`, `input.salary-input`, `data-emp-index=N` (15 Costa Rican employees, ~680K–1.34M CRC)
- `POST /employees/submit` → `confirmation.html` with `#submitted-total`, `#grand-total`, `#updated-count`
- Salaries are mutable in memory; persist across requests until reset.

## Starting the agent-worker against real AWS dev queues
```powershell
mvn.cmd spring-boot:run -pl agent-worker `
  "-Dspring-boot.run.jvmArguments=-DAWS_ACCESS_KEY_ID=... -DAWS_SECRET_ACCESS_KEY=... -DAWS_REGION=us-east-1 -DPORTAL_ID=mock-payroll"
```
See `docs/LocalWorkerRunbook.md` for the full runbook.

## Credential lookup in LocalFileCredentialsProvider
- `credentialScope: per-firm` portals → key prefix `portals.<portalId>.credentials.<clientIdentifier>.`
- `credentialScope: shared` portals → key prefix `portals.<portalId>.credentials.`
- `params.clientIdentifier` (not `params.firmId`) is the field that drives the per-firm suffix.
- Capture listener does NOT set `params.clientIdentifier`, so mock-payroll capture always uses the shared-style prefix regardless of `credentialScope`.

## businessKey passthrough rule
The worker never synthesizes a `businessKey`. Every result envelope echoes `request.envelope().businessKey()` verbatim — both inside the envelope JSON and as the SQS message attribute. Changing this breaks Praxis BPM correlation.

## GitHub Packages — published artifacts

Three Maven artifacts are published to GitHub Packages (`com.neoproc.financialagent`):

| Artifact | What it contains |
|---|---|
| `finance-agent-root` | Parent POM — dependency versions, plugin config |
| `common-lib` | Shared records, BigDecimal serializers, payload comparators |
| `contract-api` | JSON Schema files (`schemas/v1/*.json`) + Java envelope POJOs + `SchemaValidator` |

**Praxis BPM** consumes `contract-api` (and transitively `common-lib`) from GitHub Packages to validate incoming result envelopes. If local commits are not pushed, Praxis builds against stale JARs.

### When you must push to republish

A push to `main` is required (and sufficient) whenever any of these change:

- `contract-api/src/main/resources/schemas/v1/*.json` — schema additions or enum changes (e.g. new error `category` values)
- `contract-api/src/main/java/**` — Java API changes that Praxis imports
- `common-lib/src/main/java/**` — shared records or serializers
- Root `pom.xml` — dependency version bumps inherited by consumers

Changes confined to `agent-worker/`, `agent-gateway/`, `testing-harness/`, or `mcp-payroll-server/` do **not** require a republish — those modules are not published to GitHub Packages.

### How publishing works

`.github/workflows/publish-contract-api.yml` triggers automatically on push to `main` when `contract-api/**` or `pom.xml` changes. It runs:

```
mvn -pl contract-api -am deploy
```

The `-am` (also-make) flag rebuilds and redeploys all three JARs in dependency order: root → common-lib → contract-api. No manual step is needed beyond the push.

**Version:** `1.0.0-SNAPSHOT` — Praxis resolves the latest published SNAPSHOT each time it rebuilds. No version bump required for iterative development.
