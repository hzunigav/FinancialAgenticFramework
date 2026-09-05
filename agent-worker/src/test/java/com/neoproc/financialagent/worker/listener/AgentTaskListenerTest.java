package com.neoproc.financialagent.worker.listener;

import com.neoproc.financialagent.contract.agent.AgentTaskResult;
import com.neoproc.financialagent.worker.PortalRunService;
import com.neoproc.financialagent.worker.RunOutcome;
import com.neoproc.financialagent.worker.artifact.S3ArtifactStore;
import com.neoproc.financialagent.worker.idempotency.InMemoryIdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessageHeaders;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Listener-level tests for the generic bot dispatch. The properties that matter
 * to a BPM consumer are that the right bot runs, the businessKey comes back
 * untouched, and the result says where in S3 the output can be fetched.
 */
class AgentTaskListenerTest {

    private static final String BUSINESS_KEY = "NEOPROC::ccss-reports::082026";

    private PortalRunService runService;
    private SqsRetryablePublisher publisher;
    private AgentTaskListener listener;

    @TempDir
    Path runDir;

    @BeforeEach
    void setUp() {
        runService = mock(PortalRunService.class);
        publisher = mock(SqsRetryablePublisher.class);
        listener = new AgentTaskListener(
                runService, publisher, new InMemoryIdempotencyStore(), "results-queue");
    }

    @Test
    void runsTheNamedBotAndReportsWhereTheFilesLanded() throws Exception {
        when(runService.run(eq("ccss-sicere-reports"), any(), anyMap()))
                .thenReturn(outcome("SUCCESS"));

        listener.onMessage(request("ccss-sicere-reports"), headers(1));

        AgentTaskResult result = capturePublished();
        assertEquals("SUCCESS", result.status());
        assertEquals("s3://bucket/dev/runs/ccss-sicere-reports/run-1/",
                result.artifacts().folder(),
                "consumer needs the folder to fetch documents without rebuilding key layout");
        assertEquals(1, result.artifacts().files().size());
        assertEquals("reports/PlanillaNeoprocSociedadAnonima082026.pdf",
                result.artifacts().files().get(0).name());
        assertTrue(result.artifacts().files().get(0).uri().startsWith("s3://"));
    }

    @Test
    void businessKeyIsEchoedVerbatimSoTheBpmCanCorrelate() throws Exception {
        when(runService.run(anyString(), any(), anyMap())).thenReturn(outcome("SUCCESS"));

        listener.onMessage(request("ccss-sicere-reports"), headers(1));

        assertEquals(BUSINESS_KEY, capturePublished().envelope().businessKey());
    }

    @Test
    void paramsReachTheRunAsBindings() throws Exception {
        when(runService.run(anyString(), any(), anyMap())).thenReturn(outcome("SUCCESS"));

        listener.onMessage(request("ccss-sicere-reports"), headers(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> bindings = ArgumentCaptor.forClass(Map.class);
        verify(runService).run(eq("ccss-sicere-reports"), any(), bindings.capture());
        assertEquals("08/2026", bindings.getValue().get("params.period"));
        assertEquals("3101680139", bindings.getValue().get("params.clientIdentifier"));
        assertEquals("1", bindings.getValue().get("params.firmId"));
    }

    @Test
    void unknownBotFailsWithoutRunningAnything() throws Exception {
        listener.onMessage(request("no-such-bot"), headers(1));

        AgentTaskResult result = capturePublished();
        assertEquals("FAILED", result.status());
        // Redelivery cannot make an unregistered bot resolvable, so this must
        // be reported as a contract error rather than retried forever.
        assertEquals("SCHEMA_VIOLATION", result.error().category());
        verify(runService, never()).run(anyString(), any(), anyMap());
    }

    @Test
    void coldDuplicateDoesNotRunTheBotASecondTime() throws Exception {
        listener.onMessage(request("ccss-sicere-reports"), headers(2));

        AgentTaskResult result = capturePublished();
        assertEquals("FAILED", result.status());
        assertEquals("EXPIRED_DUPLICATE", result.error().category());
        verify(runService, never()).run(anyString(), any(), anyMap());
    }

    @ParameterizedTest
    @CsvSource({
            "SUCCESS,SUCCESS",
            "CAPTURED,SUCCESS",
            "PARTIAL,PARTIAL",
            // Statuses outside the contract's three collapse to FAILED so a
            // consumer never has to branch on something the schema omits.
            "MISMATCH,FAILED",
            "SHADOW_HALT,FAILED"
    })
    void runStatusIsNormalisedToTheContractsThree(String runStatus, String expected) throws Exception {
        when(runService.run(anyString(), any(), anyMap())).thenReturn(outcome(runStatus));

        listener.onMessage(request("ccss-sicere-reports"), headers(1));

        assertEquals(expected, capturePublished().status());
    }

    // --- helpers ------------------------------------------------------------

    private AgentTaskResult capturePublished() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq("results-queue"), payload.capture(),
                anyString(), anyString(), anyString());
        Object published = payload.getValue();
        assertNotNull(published, "listener published nothing");
        return (AgentTaskResult) published;
    }

    private RunOutcome outcome(String status) {
        String folder = "s3://bucket/dev/runs/ccss-sicere-reports/run-1/";
        return new RunOutcome(
                runDir, status, null,
                folder + "manifest.json",
                folder,
                List.of(new S3ArtifactStore.StoredArtifact(
                        "reports/PlanillaNeoprocSociedadAnonima082026.pdf",
                        folder + "reports/PlanillaNeoprocSociedadAnonima082026.pdf",
                        367279)));
    }

    private static MessageHeaders headers(int receiveCount) {
        return new MessageHeaders(Map.of("ApproximateReceiveCount", String.valueOf(receiveCount)));
    }

    /** A schema-valid agent-task-request.v1 message. */
    private static String request(String botId) {
        return """
                {
                  "schema": "agent-task-request.v1",
                  "envelope": {
                    "envelopeId": "3f1a6c7e-9b2d-4c5a-8e11-0d2b7a4c9f30",
                    "businessKey": "%s",
                    "firmId": 1,
                    "locale": "es",
                    "createdAt": "2026-09-04T18:00:00Z",
                    "issuer": "praxis",
                    "issuerRunId": "proc-inst-4711"
                  },
                  "task": {
                    "botId": "%s",
                    "params": {
                      "clientIdentifier": "3101680139",
                      "period": "08/2026",
                      "formats": "pdf,xls"
                    }
                  }
                }""".formatted(BUSINESS_KEY, botId);
    }
}
