# ── Stage 1: build fat JAR ────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-jammy AS builder
WORKDIR /build

# POM files before sources — the dependency-download layer is cached
# independently, so a source-only change skips the slow mvn download step.
# All six module POMs are required: Maven reads the full reactor to resolve
# the dependency graph before filtering down to the -pl / -am subset.
COPY pom.xml .
COPY common-lib/pom.xml           common-lib/pom.xml
COPY contract-api/pom.xml         contract-api/pom.xml
COPY agent-worker/pom.xml         agent-worker/pom.xml
COPY mcp-payroll-server/pom.xml   mcp-payroll-server/pom.xml
COPY agent-gateway/pom.xml        agent-gateway/pom.xml
COPY testing-harness/pom.xml      testing-harness/pom.xml

RUN mvn -pl agent-worker -am dependency:go-offline -q

# Sources for the three compiled modules only (mcp-payroll-server, agent-gateway,
# testing-harness are not in the agent-worker sub-reactor).
COPY common-lib/src   common-lib/src
COPY contract-api/src contract-api/src
COPY agent-worker/src agent-worker/src

RUN mvn -pl agent-worker -am package -DskipTests -q

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
# mcr.microsoft.com/playwright/java:v1.48.0-jammy (Ubuntu 22.04) ships with:
#   • Chromium / Firefox / WebKit binaries at /ms-playwright
#   • PLAYWRIGHT_BROWSERS_PATH=/ms-playwright (pre-set — no install step needed)
#   • OpenJDK 21
#   • Non-root user pwuser (uid 1000) with the kernel capabilities Chromium needs
FROM mcr.microsoft.com/playwright/java:v1.48.0-jammy AS runtime

WORKDIR /app

COPY --from=builder --chown=pwuser:pwuser \
    /build/agent-worker/target/agent-worker-*.jar app.jar

# Artifacts dir must be writable at runtime; bind-mount or emptyDir in ECS/k8s.
RUN mkdir -p /app/artifacts && chown pwuser:pwuser /app/artifacts

USER pwuser

# ── Runtime defaults ──────────────────────────────────────────────────────────
# Override all of these via env vars / ECS task-definition environment + secrets blocks.
# Production values per PraxisIntegrationHandoff §F.2:
#   FINANCEAGENT_CIPHER=kms
#   FINANCEAGENT_CREDENTIALS=aws
#   RABBITMQ_* injected from Secrets Manager
ENV PORTAL_ID=mock-payroll \
    ARTIFACTS_DIR=/app/artifacts \
    FINANCEAGENT_CIPHER=local \
    FINANCEAGENT_CREDENTIALS=local \
    RABBITMQ_HOST=rabbitmq \
    RABBITMQ_PORT=5672 \
    RABBITMQ_USERNAME=guest \
    RABBITMQ_PASSWORD=guest \
    RABBITMQ_VHOST=/

# 8080 = Spring Boot HTTP (health + /actuator/prometheus)
EXPOSE 8080

# -XX:MaxRAMPercentage leaves ~25 % headroom for Chromium inside the same container.
# -Djava.awt.headless prevents AWT from trying to open a display.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.awt.headless=true", \
    "-jar", "/app/app.jar"]
