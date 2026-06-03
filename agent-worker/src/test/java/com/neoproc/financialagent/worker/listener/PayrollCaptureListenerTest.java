package com.neoproc.financialagent.worker.listener;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neoproc.financialagent.contract.payroll.CaptureResultBody;
import com.neoproc.financialagent.contract.payroll.CaptureTask;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.payroll.PayrollCaptureRequest;
import com.neoproc.financialagent.contract.payroll.PayrollCaptureResult;
import com.neoproc.financialagent.contract.payroll.Planilla;
import com.neoproc.financialagent.contract.validation.SchemaValidator;
import com.neoproc.financialagent.worker.PortalRunService;
import com.neoproc.financialagent.worker.RunOutcome;
import com.neoproc.financialagent.worker.idempotency.InMemoryIdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessageHeaders;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Listener-level tests for the SQS capture flow with a focus on the
 * login-probe dispatch path added alongside the
 * {@link com.neoproc.financialagent.worker.PortalAuthService} extraction.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code businessKey: probe::*} routes to
 *       {@link PortalRunService#runProbe} (not {@link PortalRunService#run}).</li>
 *   <li>Probe result body conforms to schema: status SUCCESS/FAILED,
 *       {@code employees=[]}, zero totals, {@code businessKey} echoed
 *       verbatim per the businessKey-passthrough rule.</li>
 *   <li>Per-client portals (ccss-sicere): {@code task.planilla.id} is copied
 *       into {@code params.clientIdentifier} so the right per-client secret
 *       resolves. Shared-scope probes leave the binding unset.</li>
 *   <li>Non-probe businessKey continues to route to the normal capture path.</li>
 *   <li>{@code SchemaValidator.validate} is exercised implicitly on every
 *       published result (the publisher mock would otherwise observe an
 *       invalid envelope before reaching the listener's own asserts).</li>
 * </ul>
 *
 * <p>Default {@code FINANCEAGENT_CIPHER} (cleartext) keeps the probe result
 * body inspectable — no Vault/KMS infrastructure is touched.
 */
class PayrollCaptureListenerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final long FIRM_ID = 1L;
    private static final String PORTAL_ID = "ccss-sicere";
    private static final String RESULTS_QUEUE = "test-financeagent-results";
    private static final String PROBE_BUSINESS_KEY = "probe::ccss-sicere::2026-05-18";
    private static final String CLIENT_IDENTIFIER = "3-101-680139";
    // 64-char lowercase hex — schema validator accepts; mirrors what
    // PortalRunService.runProbe returns for a successful screenshot.
    private static final String SCREENSHOT_SHA = "a".repeat(64);

    private PortalRunService portalRunService;
    private SqsRetryablePublisher publisher;
    private InMemoryIdempotencyStore idempotencyStore;
    private PayrollCaptureListener listener;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        portalRunService = mock(PortalRunService.class);
        publisher = mock(SqsRetryablePublisher.class);
        idempotencyStore = new InMemoryIdempotencyStore();

        listener = new PayrollCaptureListener(portalRunService, idempotencyStore, publisher);
        ReflectionTestUtils.setField(listener, "portalId", PORTAL_ID);
        ReflectionTestUtils.setField(listener, "resultsQueue", RESULTS_QUEUE);

        // Default mock: probe succeeds with a synthetic SHA-256.
        lenient().when(portalRunService.runProbe(eq(PORTAL_ID), anyMap()))
                .thenReturn(new RunOutcome(tempDir, "SUCCESS", SCREENSHOT_SHA));
    }

    // -----------------------------------------------------------------------
    // Probe dispatch
    // -----------------------------------------------------------------------

    @Test
    void probeBusinessKeyRoutesToRunProbeNotRun() throws Exception {
        String envelopeId = UUID.randomUUID().toString();
        listener.onCaptureRequest(
                buildProbeRequestJson(envelopeId, CLIENT_IDENTIFIER),
                envelopeId,
                headersWithReceiveCount(1));

        verify(portalRunService, times(1)).runProbe(eq(PORTAL_ID), anyMap());
        verify(portalRunService, never()).run(anyString(), any(), anyMap());
    }

    @Test
    void probeResultIsSchemaValidAndEchoesBusinessKey() throws Exception {
        String envelopeId = UUID.randomUUID().toString();
        listener.onCaptureRequest(
                buildProbeRequestJson(envelopeId, CLIENT_IDENTIFIER),
                envelopeId,
                headersWithReceiveCount(1));

        PayrollCaptureResult published = capturePublished();

        // businessKey passthrough — Praxis correlation depends on this byte-for-byte.
        assertEquals(PROBE_BUSINESS_KEY, published.envelope().businessKey());
        // The result envelope gets a fresh envelopeId (not the request's) —
        // each envelope is independently identifiable. issuerRunId is the
        // field that links the result back to the request.
        assertEquals("run-" + envelopeId, published.envelope().issuerRunId());

        // Probe body: SUCCESS, empty employees, zero totals.
        CaptureResultBody body = (CaptureResultBody) published.result();
        assertEquals("SUCCESS", body.status());
        assertTrue(body.employees().isEmpty(), "probe result must carry an empty employees array");
        assertEquals(0, body.totals().employeeCount());
        assertEquals("CRC", body.totals().currency());

        // Audit: probe-supplied screenshot SHA propagates verbatim;
        // payloadSha256 is computed by EnvelopeIo.encryptBody.
        assertEquals(SCREENSHOT_SHA, published.audit().screenshotSha256());
        assertNotNull(published.audit().payloadSha256());
        assertTrue(published.audit().payloadSha256().matches("[0-9a-f]{64}"));

        // task echo for BPMN routing.
        assertEquals(PORTAL_ID, published.task().sourcePortal());
        assertEquals("PAYROLL_CAPTURE", published.task().type());

        // Belt-and-suspenders: re-run the validator against the published envelope
        // so a future schema tightening breaks this test, not Praxis at runtime.
        SchemaValidator.validate(published, SchemaValidator.CAPTURE_RESULT);
    }

    @Test
    void probeWithPlanillaIdCopiesClientIdentifierIntoBindings() throws Exception {
        String envelopeId = UUID.randomUUID().toString();
        listener.onCaptureRequest(
                buildProbeRequestJson(envelopeId, CLIENT_IDENTIFIER),
                envelopeId,
                headersWithReceiveCount(1));

        Map<String, String> bindings = captureProbeBindings();
        assertEquals(CLIENT_IDENTIFIER, bindings.get("params.clientIdentifier"),
                "per-client portals: task.planilla.id must populate params.clientIdentifier "
                        + "so the per-client credentials path resolves");
        assertEquals(String.valueOf(FIRM_ID), bindings.get("params.firmId"));
        assertEquals(PROBE_BUSINESS_KEY, bindings.get("params.businessKey"));
    }

    @Test
    void sharedScopeProbeWithoutPlanillaLeavesClientIdentifierUnset() throws Exception {
        // No planilla on the request — typical for shared-scope portals
        // (autoplanilla, ins-rt-virtual) where one credential serves all firms.
        String envelopeId = UUID.randomUUID().toString();
        listener.onCaptureRequest(
                buildProbeRequestJson(envelopeId, /*clientIdentifier=*/ null),
                envelopeId,
                headersWithReceiveCount(1));

        Map<String, String> bindings = captureProbeBindings();
        assertFalse(bindings.containsKey("params.clientIdentifier"),
                "shared-scope probe must NOT set params.clientIdentifier — "
                        + "doing so would push LocalFileCredentialsProvider down the per-firm path");
    }

    @Test
    void probeFailureMapsRunStatusToFailedBody() throws Exception {
        // Simulate runProbe returning FAILED with no screenshot — e.g.
        // CREDENTIALS_INVALID, the post-login selector never matched.
        when(portalRunService.runProbe(eq(PORTAL_ID), anyMap()))
                .thenReturn(new RunOutcome(tempDir, "FAILED", null));

        String envelopeId = UUID.randomUUID().toString();
        listener.onCaptureRequest(
                buildProbeRequestJson(envelopeId, CLIENT_IDENTIFIER),
                envelopeId,
                headersWithReceiveCount(1));

        PayrollCaptureResult published = capturePublished();
        CaptureResultBody body = (CaptureResultBody) published.result();
        assertEquals("FAILED", body.status());
        assertTrue(body.employees().isEmpty());
        assertNull(published.audit().screenshotSha256(),
                "failed probe with no screenshot must leave audit.screenshotSha256 null "
                        + "(schema allows null; sending an empty string would fail the hex pattern)");

        SchemaValidator.validate(published, SchemaValidator.CAPTURE_RESULT);
    }

    // -----------------------------------------------------------------------
    // Non-probe sanity — the businessKey prefix must NOT bleed onto normal captures
    // -----------------------------------------------------------------------

    @Test
    void nonProbeBusinessKeyDispatchesNormalCapture() throws Exception {
        // Stub run() — normal capture path returns a runDir; the listener
        // falls back to buildMinimalResult when no result file is present,
        // which is fine for verifying dispatch.
        when(portalRunService.run(eq(PORTAL_ID), any(), anyMap()))
                .thenReturn(new RunOutcome(tempDir, "SUCCESS"));

        String envelopeId = UUID.randomUUID().toString();
        listener.onCaptureRequest(
                buildCaptureRequestJson(envelopeId, "2026-05::ccss-sicere::" + CLIENT_IDENTIFIER),
                envelopeId,
                headersWithReceiveCount(1));

        verify(portalRunService, times(1)).run(eq(PORTAL_ID), any(), anyMap());
        verify(portalRunService, never()).runProbe(anyString(), anyMap());
    }

    // -----------------------------------------------------------------------
    // Multi-planilla binding extraction
    // -----------------------------------------------------------------------

    @Test
    void captureRequestWithPlanillas_bindsPlanillasJsonAndFirstAsRepresentative() throws Exception {
        when(portalRunService.run(eq(PORTAL_ID), any(), anyMap()))
                .thenReturn(new RunOutcome(tempDir, "SUCCESS"));

        String envelopeId = UUID.randomUUID().toString();
        listener.onCaptureRequest(
                buildPlanillasRequestJson(envelopeId, "2026-05::3-101-578509",
                        new Planilla("1051", "FEUJI Costa Rica USD"),
                        new Planilla("1052", "FEUJI Costa Rica CRC")),
                envelopeId,
                headersWithReceiveCount(1));

        Map<String, String> bindings = captureRunBindings();

        // Representative singular bindings come from the first planilla.
        assertEquals("1051", bindings.get("params.planillaId"));
        assertEquals("FEUJI Costa Rica USD", bindings.get("params.planillaName"));

        // The full ordered list the AutoPlanilla adapter loops over.
        String json = bindings.get("params.planillasJson");
        assertNotNull(json, "params.planillasJson must carry the full planilla list");
        List<Planilla> parsed = MAPPER.readValue(json, new TypeReference<List<Planilla>>() {});
        assertEquals(2, parsed.size());
        assertEquals("1051", parsed.get(0).id());
        assertEquals("1052", parsed.get(1).id());
    }

    @Test
    void captureRequestWithSingularPlanilla_stillEmitsOneElementPlanillasJson() throws Exception {
        when(portalRunService.run(eq(PORTAL_ID), any(), anyMap()))
                .thenReturn(new RunOutcome(tempDir, "SUCCESS"));

        String envelopeId = UUID.randomUUID().toString();
        PayrollCaptureRequest request = new PayrollCaptureRequest(
                PayrollCaptureRequest.SCHEMA,
                new EnvelopeMeta(envelopeId, "2026-05::3-101-578509", FIRM_ID, "es",
                        Instant.now(), "praxis", "run-" + envelopeId),
                CaptureTask.of(PORTAL_ID, null, new Planilla("1051", "FEUJI Costa Rica USD")),
                null, null, null);
        listener.onCaptureRequest(MAPPER.writeValueAsString(request), envelopeId,
                headersWithReceiveCount(1));

        Map<String, String> bindings = captureRunBindings();
        assertEquals("1051", bindings.get("params.planillaId"));
        List<Planilla> parsed = MAPPER.readValue(
                bindings.get("params.planillasJson"), new TypeReference<List<Planilla>>() {});
        assertEquals(1, parsed.size(),
                "singular task.planilla must normalise to a one-element params.planillasJson");
        assertEquals("1051", parsed.get(0).id());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, String> captureRunBindings() throws Exception {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(portalRunService).run(eq(PORTAL_ID), any(), captor.capture());
        return captor.getValue();
    }

    private String buildPlanillasRequestJson(String envelopeId, String businessKey,
                                             Planilla... planillas) throws Exception {
        PayrollCaptureRequest request = new PayrollCaptureRequest(
                PayrollCaptureRequest.SCHEMA,
                new EnvelopeMeta(envelopeId, businessKey, FIRM_ID, "es",
                        Instant.now(), "praxis", "run-" + envelopeId),
                CaptureTask.ofMulti(PORTAL_ID, null, List.of(planillas)),
                null, null, null);
        return MAPPER.writeValueAsString(request);
    }

    private PayrollCaptureResult capturePublished() {
        ArgumentCaptor<PayrollCaptureResult> captor = ArgumentCaptor.forClass(PayrollCaptureResult.class);
        verify(publisher).publish(eq(RESULTS_QUEUE), captor.capture(),
                eq(PayrollCaptureResult.SCHEMA), anyString(), anyString());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> captureProbeBindings() throws Exception {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(portalRunService).runProbe(eq(PORTAL_ID), captor.capture());
        return captor.getValue();
    }

    private String buildProbeRequestJson(String envelopeId, String clientIdentifier) throws Exception {
        Planilla planilla = clientIdentifier == null
                ? null
                : new Planilla(clientIdentifier, "probe");
        PayrollCaptureRequest request = new PayrollCaptureRequest(
                PayrollCaptureRequest.SCHEMA,
                new EnvelopeMeta(envelopeId, PROBE_BUSINESS_KEY, FIRM_ID, "es",
                        Instant.now(), "praxis-smoke", "run-" + envelopeId),
                CaptureTask.of(PORTAL_ID, null, planilla),
                null, null, null);
        return MAPPER.writeValueAsString(request);
    }

    private String buildCaptureRequestJson(String envelopeId, String businessKey) throws Exception {
        PayrollCaptureRequest request = new PayrollCaptureRequest(
                PayrollCaptureRequest.SCHEMA,
                new EnvelopeMeta(envelopeId, businessKey, FIRM_ID, "es",
                        Instant.now(), "praxis", "run-" + envelopeId),
                CaptureTask.of(PORTAL_ID, null, null),
                null, null, null);
        return MAPPER.writeValueAsString(request);
    }

    private static MessageHeaders headersWithReceiveCount(int n) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("Sqs_ApproximateReceiveCount", String.valueOf(n));
        return new MessageHeaders(raw);
    }
}
