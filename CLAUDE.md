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
