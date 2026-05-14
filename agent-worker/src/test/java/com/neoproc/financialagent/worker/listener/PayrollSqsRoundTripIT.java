package com.neoproc.financialagent.worker.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitRequest;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitResult;
import com.neoproc.financialagent.contract.payroll.RosterDiff;
import com.neoproc.financialagent.contract.payroll.SubmitRequestBody;
import com.neoproc.financialagent.contract.payroll.SubmitResultBody;
import com.neoproc.financialagent.contract.payroll.SubmitTask;
import com.neoproc.financialagent.worker.PortalRunService;
import com.neoproc.financialagent.worker.RunOutcome;
import com.neoproc.financialagent.worker.WorkerApplication;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * End-to-end round-trip test against a real (LocalStack) SQS endpoint.
 *
 * <p>Validates that the {@link PayrollTaskListener} receives a request
 * envelope from the submit queue, mock-runs the portal, and publishes a
 * schema-valid result envelope to the results queue. Exercises the full
 * Spring Cloud AWS wiring — annotation processing, message conversion,
 * MessageAttribute round-trip — which unit tests cannot reach.
 *
 * <p>{@link PortalRunService} is replaced with a {@code @MockBean} so
 * Playwright is never invoked. The mock returns a {@link RunOutcome}
 * pointing at a pre-built {@code payroll-submit-result.v1.json} in a
 * {@code @TempDir}.
 *
 * <p>Requires Docker. Tagged as {@code *IT} so it runs under
 * {@code mvn verify} (failsafe) rather than {@code mvn test} (surefire).
 */
@SpringBootTest(classes = WorkerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class PayrollSqsRoundTripIT {

    private static final ObjectMapper REQUEST_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String PORTAL_ID = "mock-payroll";
    private static final String QUEUE_PREFIX = "test";
    private static final String SUBMIT_QUEUE = QUEUE_PREFIX + "-financeagent-tasks-submit-" + PORTAL_ID;
    private static final String CAPTURE_QUEUE = QUEUE_PREFIX + "-financeagent-tasks-capture-" + PORTAL_ID;
    private static final String RESULTS_QUEUE = QUEUE_PREFIX + "-financeagent-results";

    private static final String BUSINESS_KEY = "it-business-key";
    private static final long FIRM_ID = 1L;

    @Container
    @SuppressWarnings("resource")  // testcontainers manages container lifecycle via @Container.
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.0"))
            .withServices(LocalStackContainer.Service.SQS);

    private static SqsClient testClient;
    private static String submitQueueUrl;
    private static String resultsQueueUrl;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.region.static", () -> LOCALSTACK.getRegion());
        registry.add("spring.cloud.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.sqs.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
        registry.add("agent.worker.queue-prefix", () -> QUEUE_PREFIX);
        registry.add("portal.id", () -> PORTAL_ID);
    }

    @BeforeAll
    static void createQueues() {
        testClient = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();

        // Pre-create queues — both task queues (so listener bean creation succeeds)
        // and the results queue (so the worker has somewhere to publish).
        testClient.createQueue(CreateQueueRequest.builder().queueName(SUBMIT_QUEUE).build());
        testClient.createQueue(CreateQueueRequest.builder().queueName(CAPTURE_QUEUE).build());
        submitQueueUrl = testClient.getQueueUrl(b -> b.queueName(SUBMIT_QUEUE)).queueUrl();
        resultsQueueUrl = testClient.createQueue(
                CreateQueueRequest.builder().queueName(RESULTS_QUEUE).build()).queueUrl();
    }

    @MockBean
    PortalRunService portalRunService;

    @TempDir
    Path runDir;

    @BeforeEach
    void prepRunDir() throws Exception {
        // Pre-build a schema-valid SUCCESS result file the listener will read
        // from RunOutcome.runDir() on this mock-payroll run.
        SubmitResultBody body = new SubmitResultBody(
                "SUCCESS", null,
                new SubmitResultBody.Totals("CRC", new BigDecimal("500000"), 1),
                List.of(),
                new RosterDiff(List.of(), List.of()),
                null, null);
        PayrollSubmitResult mockResult = new PayrollSubmitResult(
                PayrollSubmitResult.SCHEMA,
                new EnvelopeMeta(UUID.randomUUID().toString(), BUSINESS_KEY, FIRM_ID, "es",
                        Instant.now(), "test", "it-run"),
                SubmitTask.forSalaries(PORTAL_ID, null, null, null),
                null, body,
                new Audit("none", null, null, EnvelopeIo.sha256Hex("it-fixture-payload")));
        EnvelopeIo.MAPPER.writeValue(runDir.resolve("payroll-submit-result.v1.json").toFile(), mockResult);

        when(portalRunService.run(eq(PORTAL_ID), any(PayrollSubmitRequest.class), anyMap()))
                .thenReturn(new RunOutcome(runDir, "SUCCESS"));
    }

    @Autowired
    @SuppressWarnings("unused")  // Spring context wiring is what we're testing — autowire surfaces failures.
    org.springframework.context.ApplicationContext context;

    @Test
    void requestOnSubmitQueueProducesResultOnResultsQueue() throws Exception {
        String envelopeId = UUID.randomUUID().toString();
        PayrollSubmitRequest request = new PayrollSubmitRequest(
                "payroll-submit-request.v1",
                new EnvelopeMeta(envelopeId, BUSINESS_KEY, FIRM_ID, "es",
                        Instant.now(), "praxis", "it-issuer-run"),
                SubmitTask.forSalaries(PORTAL_ID, null, null, null),
                null,
                new SubmitRequestBody(List.of(
                        new SubmitRequestBody.SubmitEmployee(
                                "E001", "Alice", new BigDecimal("500000"), Map.of()))),
                null);
        String body = REQUEST_MAPPER.writeValueAsString(request);

        testClient.sendMessage(b -> b.queueUrl(submitQueueUrl).messageBody(body));

        await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ofMillis(500)).untilAsserted(() -> {
            ReceiveMessageRequest recv = ReceiveMessageRequest.builder()
                    .queueUrl(resultsQueueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(2)
                    .messageAttributeNames("All")
                    .build();
            List<Message> messages = testClient.receiveMessage(recv).messages();
            assertFalse(messages.isEmpty(), "expected a result envelope on " + RESULTS_QUEUE);

            Message m = messages.get(0);
            PayrollSubmitResult result = EnvelopeIo.MAPPER
                    .readValue(m.body(), PayrollSubmitResult.class);
            assertEquals(BUSINESS_KEY, result.envelope().businessKey(),
                    "businessKey passthrough — result must echo the request's businessKey");
            assertEquals(PayrollSubmitResult.SCHEMA, result.schema());
        });
    }
}
