# Deployment Plan: Financial Agentic Framework v3.0

This document outlines the high-level plan for deploying the Financial Agentic Framework. It is divided into six phases, from infrastructure setup to production go-live.

---

### Phase 1: Infrastructure Provisioning (AWS)

*   **Objective**: Create the secure and compliant AWS environment as defined in the technical specification.
*   **Key Actions**:
    1.  **VPC & Networking**: Deploy the VPC, public/private subnets, NAT Gateway with a Static Elastic IP, and necessary VPC Endpoints (S3, KMS, Secrets Manager) using the `infra-aws` CDK module.
    2.  **Storage**: Provision the S3 bucket with a 7-year WORM (Write Once Read Many) policy for audit traces.
    3.  **Database**: Set up the DynamoDB table for execution state checkpointing.
    4.  **Messaging**: Deploy and configure RabbitMQ (or Amazon MQ for RabbitMQ) for the "Safe-Submit" loop.
    5.  **Security & IAM**: Create fine-grained IAM roles and policies for all services (Fargate, MCP Server, etc.) to enforce the principle of least privilege.
    6.  **Secrets Management**: Configure AWS Secrets Manager to store all sensitive data, such as API keys and credentials.
    7.  **Encryption**: Set up customer-managed KMS keys for encrypting data at rest and in transit.

---

### Phase 2: Core Services Deployment

*   **Objective**: Deploy the central backend components of the framework.
*   **Key Actions**:
    1.  **Build**: Set up a CI pipeline (e.g., Jenkins, GitHub Actions) to build all Maven modules (`common-lib`, `contract-api`, `mcp-payroll-server`, `agent-gateway`).
    2.  **Deploy MCP Server**: Containerize and deploy the `mcp-payroll-server` as a service (e.g., on AWS Fargate or ECS).
    3.  **Deploy Agent Gateway**: Containerize and deploy the `agent-gateway` service.
    4.  **Configuration**: Externalize service configurations and inject them at runtime.
    5.  **Observability**: Configure structured logging and integrate OpenTelemetry for distributed tracing.

---

### Phase 3: Agent Worker Deployment

*   **Objective**: Deploy the Playwright-based agent workers that will perform the UI interactions.
*   **Key Actions**:
    1.  **Build**: Create a separate CI pipeline for the `agent-worker`. This should include the GraalVM Native Image compilation step to optimize performance and resource usage.
    2.  **Containerize**: Package the native executable into a minimal container image.
    3.  **Store Image**: Push the container image to Amazon ECR.
    4.  **Deploy**: Configure and deploy the AWS Fargate service for the `agent-worker`, ensuring it runs in the private subnet.

---

### Phase 4: Integration & Configuration

*   **Objective**: Connect all the distributed components and ensure they communicate correctly and securely.
*   **Key Actions**:
    1.  **BPM Integration**: Configure the Praxis BPM to publish task messages to the correct RabbitMQ exchange.
    2.  **RabbitMQ Setup**: Define the queues, exchanges, and bindings required for the task leasing and "Safe-Submit" workflows.
    3.  **Secrets Injection**: Ensure all services correctly fetch their required secrets from AWS Secrets Manager at startup.
    4.  **Network Verification**: Perform connectivity tests to ensure the Agent Worker can reach the external government portal through the NAT Gateway and that internal traffic correctly uses VPC Endpoints.

---

### Phase 5: End-to-End Testing & Verification

*   **Objective**: Validate the entire system against the technical and business requirements before go-live.
*   **Key Actions**:
    1.  **Deploy Test Harness**: Deploy the `testing-harness` to a separate environment to simulate the government web portals.
    2.  **Execute Test Cases**: Run comprehensive end-to-end test scenarios, including:
        *   Successful payroll submission.
        *   Human-in-the-Loop (HITL) approval workflow.
        *   Fault tolerance (e.g., worker restart, "Double Submission" prevention).
        *   UI change detection (`strict: true` selector failures).
    3.  **Verify Audit Trail**: Confirm that payload hashes are verified and that immutable audit traces are correctly stored in the WORM S3 bucket.
    4.  **Confirm Observability**: Check that distributed traces are correctly captured in the observability platform (e.g., AWS X-Ray) for a complete transaction.

---

### Phase 6: Production Go-Live

*   **Objective**: Transition the system to the live production environment for operational use.
*   **Key Actions**:
    1.  **Final Review**: Conduct a final review of all configurations, IAM policies, and security settings.
    2.  **IP Whitelisting**: Coordinate with the government portal administrators to whitelist the Static Elastic IP of the NAT Gateway.
    3.  **Enable Production Monitoring**: Activate production-level monitoring, logging, and alerting for all components.
    4.  **Deployment Strategy**: Choose a go-live strategy (e.g., blue-green deployment, canary release, or a phased rollout) to minimize risk.
    5.  **Sign-off**: Obtain final sign-off from business stakeholders, compliance officers, and the security team before processing live data.
