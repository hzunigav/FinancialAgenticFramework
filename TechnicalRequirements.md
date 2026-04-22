# Technical Specification: Financial Agentic Framework (v3.0)

*Refined for Production-Grade Auditability & Resilience*

---

## 1. Executive Summary

This framework defines a **Process-Led AI** architecture. It treats the LLM not as a decision-maker, but as a **"Dynamic Translator"** between structured payroll data and unstructured government web interfaces.

- The **BPM (Praxis)** maintains the source of truth.
- **Java-based agents** execute tasks within a strictly fenced AWS environment.

---

## 2. System Topology & Network Fencing

To meet financial compliance (SOC2/HIPAA-level rigor), the **Agent Workers** must be isolated.

### VPC Architecture
- The Agent Worker (Fargate) must reside in a **Private Subnet**.
- All traffic to the government portal must exit through a **NAT Gateway with a Static Elastic IP** (required for IP-whitelisting by government firewalls).

### VPC Endpoints
- Ensure Fargate communicates with **S3, KMS, and Secret Manager** via VPC Endpoints to keep traffic off the public internet.

---

## 3. Enhanced Maven Monorepo Structure

A dedicated module for **Contract Verification** ensures the Worker and Gateway never drift.

```text
finance-agent-root
├── common-lib/             # Shared Records, Custom BigDecimal Serializers
├── contract-api/           # JSON Schemas & Protobufs for RabbitMQ messages
├── mcp-payroll-server/     # MCP Server (Spring Boot)
├── agent-gateway/          # Security Proxy + LLM Orchestrator (LangChain4j)
├── agent-worker/           # Playwright Executors (Native Image optimized)
├── testing-harness/        # Playwright "Ghost" portals for CI/CD simulation
└── infra-aws/              # AWS CDK (Typescript/Java)
```

---

## 4. Integration Logic: The MCP Server (Refined)

The **Model Context Protocol (MCP)** server acts as the **Data Governor**.

- **Context Window Optimization:** The MCP server should not just "dump" data. It must provide **paginated payroll views** to prevent the LLM from hallucinating values when dealing with large 500+ employee batches.
- **Stateless Tooling:** Tools should be designed as **idempotent operations**. If an agent calls `getEmployeeData(id)`, it must return the exact same hash every time within a single execution session.

---

## 5. The "Financial Guardrails" Protocol (Expanded)

| Phase         | Safety Mechanism         | Engineering Requirement                                                                                                   |
| ------------- | ------------------------ | ------------------------------------------------------------------------------------------------------------------------- |
| Ingestion     | Payload Hash             | BPM attaches a **SHA-256 hash** of the payroll data. Worker re-verifies this before execution.                            |
| Execution     | Shadow DOM Protection    | Playwright must use `strict: true` for selectors to **fail-fast** if the UI changes.                                      |
| Verification  | Cross-Check              | Agent performs a **"Read-Back"** and passes the scraped data to a second, "Auditor" LLM instance or a Regex-based validator. |
| Audit         | S3 Object Locking        | S3 bucket for traces must have **Write Once Read Many (WORM)** enabled for 7 years.                                       |

---

## 6. BPM & RabbitMQ Workflow: The "Safe-Submit" Loop

We must avoid **double submissions** if a container restarts.

- **Lease Management:** The Agent Worker "leases" a task from RabbitMQ with a **Visibility Timeout**.
- **State Checkpoint:** Before every click on a Submit button, the Agent checks a **DynamoDB `ExecutionState` table** to ensure that specific `Transaction_ID` isn't already marked `SUBMITTED`.

### The "Human-in-the-Loop" (HITL) Gate

1. Agent fills the form.
2. Agent takes a **full-page screenshot**.
3. Agent uploads screenshot to **S3** + sends metadata to Praxis.
4. Worker enters a **"Halt"** state (or terminates, saving state to DynamoDB to save Fargate costs).
5. Human approves in Praxis.
6. BPM triggers a new RabbitMQ message to **"Finalize Submit."**

---

## 7. Governance & Observability (The "Audit" Section)

### 7.1 OpenTelemetry & Traceability

Every agent action must be wrapped in a **Trace ID** that spans:

> Praxis BPM → RabbitMQ → Agent Worker → LLM

Use **OpenTelemetry** to export these to **AWS X-Ray** or **Honeycomb**. This allows you to see exactly which LLM prompt led to which UI interaction.

### 7.2 Deterministic Verification (The Read-Back)

> **Do not trust the LLM to verify its own work.**

- **Requirement:** The Worker must scrape the completed government form.
- **Logic:** Use a `Comparator<PayrollData>` class in `common-lib`. If `scrapedData.equals(sourceData)` returns `false`, the `agent-gateway` must trigger an **immediate C-Level Alert**.

### 7.3 PII Redaction at the Edge

The `agent-worker` must use a local **Presidio** (or similar Java-based) filter **before** sending any logs or screenshots to the Gateway.

- Salary data should **never** appear in a log message.
- Use placeholders like `[REDACTED_COMPENSATION]`.

---

## 8. Deployment Roadmap (Revised)

| Phase | Name                      | Description                                                                                             |
| ----- | ------------------------- | ------------------------------------------------------------------------------------------------------- |
| 1     | **Shadow Mode**           | Deploy "Hands" to navigate the portal but **never click submit**. Log the differences between what it would have entered vs. what a human did. |
| 2     | **MCP Integration**       | Connect real payroll data via MCP with **Read-Only** permissions.                                       |
| 3     | **BPM Gated Submission**  | Enable "Submit" only after the **"Four-Eyes"** manual approval is confirmed in the BPM.                 |
| 4     | **Autonomy**              | Move to **"Exception-Only"** human review for low-risk, small-batch payroll.                            |
