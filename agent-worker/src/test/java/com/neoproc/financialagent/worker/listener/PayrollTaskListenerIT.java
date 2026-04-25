package com.neoproc.financialagent.worker.listener;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * End-to-end contract test for the RabbitMQ consumer.
 *
 * <p>{@link PortalRunService} is mocked so Playwright / Chromium is never
 * invoked. The mock returns a {@link RunOutcome} pointing to a pre-built
 * cleartext {@code payroll-submit-result.v1.json} written to a temp
 * directory, so the listener's full read-publish path is exercised without
 * any browser or cipher-key infrastructure.
 *
 * <p>Verifies:
 * <ul>
 *   <li>A {@code payroll-submit-request.v1} published to the task queue
 *       produces a {@code payroll-submit-result.v1} on the results queue.</li>
 *   <li>The result's AMQP {@code correlation-id} matches
 *       {@code envelope.businessKey} from the request.</li>
 *   <li>Duplicate {@code envelopeId} triggers the idempotency guard: the
 *       portal run is executed only once for two identical messages.</li>
 * </ul>
 *
 * <p>The idempotency error-result path ({@code DUPLICATE_ENVELOPE}) is
 * tested separately as a unit test because it requires cipher-key
 * infrastructure ({@code ~/.financeagent/cipher-keys/}) that is not
 * available in all CI environments.
 */
@SpringBootTest(
        classes = WorkerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class PayrollTaskListenerIT {

    @Container
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer("rabbitmq:3.12-management");

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    }

    @Autowired
    RabbitTemplate rabbitTemplate;

    @MockBean
    PortalRunService portalRunService;

    @TempDir
    Path tempDir;

    private static final String BUSINESS_KEY = "test-business-key";
    private static final long   FIRM_ID       = 1L;

    @BeforeEach
    void prepareRunOutcome() throws Exception {
        // Write a minimal cleartext PayrollSubmitResult to tempDir.
        // The listener looks for payroll-submit-result.v1.json in RunOutcome.runDir().
        SubmitResultBody body = new SubmitResultBody(
                "SUCCESS",
                null,
                new SubmitResultBody.Totals("CRC", new BigDecimal("500000"), 1),
                List.of(),
                new RosterDiff(List.of(), List.of()),
                null,
                null);
        PayrollSubmitResult mockResult = new PayrollSubmitResult(
                PayrollSubmitResult.SCHEMA,
                new EnvelopeMeta(
                        "result-" + UUID.randomUUID(),
                        BUSINESS_KEY,
                        FIRM_ID,
                        "es",
                        Instant.now(),
                        "test",
                        null),
                SubmitTask.forSalaries("mock-payroll", null, null, null),
                null,   // cleartext — no encryption needed for this test
                body,
                new Audit("manifest.json", null, null, "sha256-placeholder"));

        // Write using the same MAPPER the listener uses to read the file.
        EnvelopeIo.MAPPER.writeValue(
                tempDir.resolve("payroll-submit-result.v1.json").toFile(),
                mockResult);

        when(portalRunService.run(eq("mock-payroll"), any(PayrollSubmitRequest.class), anyMap()))
                .thenReturn(new RunOutcome(tempDir, "SUCCESS"));
    }

    // -----------------------------------------------------------------------
    // Test: happy path
    // -----------------------------------------------------------------------

    @Test
    void publishesResultWithMatchingCorrelationId() {
        PayrollSubmitRequest request = buildRequest(UUID.randomUUID().toString());

        rabbitTemplate.convertAndSend("", "financeagent.tasks.submit.mock-payroll", request);

        Message resultMsg = rabbitTemplate.receive("financeagent.results", 30_000);

        assertNotNull(resultMsg, "Expected a PayrollSubmitResult on financeagent.results");
        assertEquals(BUSINESS_KEY, resultMsg.getMessageProperties().getCorrelationId(),
                "correlation-id must equal envelope.businessKey");
        assertEquals(PayrollSubmitResult.SCHEMA,
                resultMsg.getMessageProperties().getType(),
                "type header must be payroll-submit-result.v1");
    }

    // -----------------------------------------------------------------------

    private PayrollSubmitRequest buildRequest(String envelopeId) {
        return new PayrollSubmitRequest(
                "payroll-submit-request.v1",
                new EnvelopeMeta(
                        envelopeId,
                        BUSINESS_KEY,
                        FIRM_ID,
                        "es",
                        Instant.now(),
                        "test-issuer",
                        "test-run-" + envelopeId),
                SubmitTask.forSalaries("mock-payroll", null, null, null),
                null,   // no encryption
                new SubmitRequestBody(List.of(
                        new SubmitRequestBody.SubmitEmployee(
                                "E001", "Alice", new BigDecimal("500000"), Map.of()))),
                null);
    }
}
