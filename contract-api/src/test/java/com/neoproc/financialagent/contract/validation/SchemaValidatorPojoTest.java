package com.neoproc.financialagent.contract.validation;

import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.CaptureResultBody;
import com.neoproc.financialagent.contract.payroll.CaptureTask;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.payroll.PayrollCaptureResult;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitResult;
import com.neoproc.financialagent.contract.payroll.Period;
import com.neoproc.financialagent.contract.payroll.Planilla;
import com.neoproc.financialagent.contract.payroll.RosterDiff;
import com.neoproc.financialagent.contract.payroll.SubmitResultBody;
import com.neoproc.financialagent.contract.payroll.SubmitTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression coverage for the POJO validation path
 * ({@link SchemaValidator#validate(Object, String)}). The fixture-driven
 * {@link SchemaFixtureValidationTest} only exercises the byte-array path —
 * which uses ISO-string {@code createdAt} on disk — and therefore does not
 * catch producer-side serialization mistakes that would break
 * validate-on-publish.
 *
 * <p>Each case here mirrors a real failure mode hit during the 2026-04-27
 * E2E sanity run:
 * <ul>
 *   <li>Date-as-timestamp serialization breaking {@code envelope.createdAt}.</li>
 *   <li>Producer-emitted {@code totals.renta=null} being rejected.</li>
 *   <li>Listener {@code buildFailedResult} wrapping null totals into a body
 *       the schema requires to be populated.</li>
 * </ul>
 */
class SchemaValidatorPojoTest {

    @Test
    @DisplayName("PayrollCaptureResult POJO with java.time.Instant createdAt validates against capture-result schema")
    void capturePojoWithInstantCreatedAt_validates() {
        PayrollCaptureResult result = new PayrollCaptureResult(
                PayrollCaptureResult.SCHEMA,
                new EnvelopeMeta(
                        UUID.randomUUID().toString(),
                        "2026-01::Planilla Regular",
                        1351L,
                        "es",
                        Instant.now(),                 // critical: real Instant, not pre-formatted string
                        "agent-worker/mock-payroll",
                        "run-1"),
                CaptureTask.of("mock-payroll", null, null),
                null,                                  // cleartext mode — no encryption block
                new CaptureResultBody(
                        "SUCCESS",
                        new CaptureResultBody.Totals("CRC",
                                new BigDecimal("13870000.00"),
                                new BigDecimal("1583210.00"),
                                15),
                        List.of()),
                new Audit("manifest.json", null, null,
                        "0000000000000000000000000000000000000000000000000000000000000000"));

        // Pre-fix this would fail with: $.envelope.createdAt: number found, string expected
        SchemaValidator.validate(result, SchemaValidator.CAPTURE_RESULT);
    }

    @Test
    @DisplayName("CaptureResultBody with totals.renta=null is accepted (mock-payroll, legacy portals)")
    void renta_null_isAccepted() {
        PayrollCaptureResult result = new PayrollCaptureResult(
                PayrollCaptureResult.SCHEMA,
                envMeta(),
                CaptureTask.of("mock-payroll", null, null),
                null,
                new CaptureResultBody(
                        "SUCCESS",
                        new CaptureResultBody.Totals("CRC",
                                new BigDecimal("13870000.00"),
                                null,                  // renta unknown — schema must permit
                                15),
                        List.of()),
                audit());

        SchemaValidator.validate(result, SchemaValidator.CAPTURE_RESULT);
    }

    @Test
    @DisplayName("FAILED capture result with zero-populated totals validates")
    void capturePojo_failedWithZeroTotals_validates() {
        PayrollCaptureResult result = new PayrollCaptureResult(
                PayrollCaptureResult.SCHEMA,
                envMeta(),
                CaptureTask.of("mock-payroll", null, null),
                null,
                new CaptureResultBody(
                        "FAILED",
                        new CaptureResultBody.Totals("CRC", BigDecimal.ZERO, null, 0),
                        List.of()),
                audit());

        SchemaValidator.validate(result, SchemaValidator.CAPTURE_RESULT);
    }

    @Test
    @DisplayName("FAILED submit result with zero-populated totals validates")
    void submitPojo_failedWithZeroTotals_validates() {
        PayrollSubmitResult result = new PayrollSubmitResult(
                PayrollSubmitResult.SCHEMA,
                envMeta(),
                SubmitTask.forSalaries("mock-payroll", null, null, null),
                null,
                new SubmitResultBody(
                        "FAILED",
                        null,
                        new SubmitResultBody.Totals("CRC", BigDecimal.ZERO, 0),
                        List.of(),
                        new RosterDiff(List.of(), List.of()),
                        new SubmitResultBody.ErrorDetail(
                                "agent-worker.PayrollTaskListener",
                                "UNCAUGHT_EXCEPTION",
                                "test"),
                        null),
                audit());

        SchemaValidator.validate(result, SchemaValidator.SUBMIT_RESULT);
    }

    @Test
    @DisplayName("Submit result with expected/portalReported totals (per-client portal) validates")
    void submitPojo_perClientTotals_validates() {
        PayrollSubmitResult result = new PayrollSubmitResult(
                PayrollSubmitResult.SCHEMA,
                envMeta(),
                SubmitTask.forSalaries("ccss-sicere", null,
                        "3101680139", null, null),
                null,
                new SubmitResultBody(
                        "SUCCESS",
                        null,
                        new SubmitResultBody.Totals(
                                "CRC",
                                new BigDecimal("18440999.90"),  // grandTotal == portalReported
                                16,
                                new BigDecimal("18440999.90"),  // expected
                                new BigDecimal("18440999.90")), // portalReported
                        List.of(),
                        new RosterDiff(List.of(), List.of()),
                        null,
                        null),
                audit());

        SchemaValidator.validate(result, SchemaValidator.SUBMIT_RESULT);
    }

    @Test
    @DisplayName("Submit result rejects period+planilla on the task block (regression)")
    void submitPojo_resultTaskWithPeriodOrPlanilla_failsValidation() {
        // The result schema's task block declares additionalProperties:false
        // and does NOT allow period / planilla — those belong only to the
        // request side. Pre-fix, PayrollTaskListener.wrapResult echoed
        // request.task() verbatim into FAILED / DUPLICATE_ENVELOPE / minimal
        // result envelopes, which then failed validate-on-publish and DLQ'd
        // every error result so Praxis never heard back. Lock that in.
        SubmitTask resultTaskWithRequestFields = new SubmitTask(
                "PAYROLL_SUBMIT", "SUBMIT_SALARIES",
                "ccss-sicere", null, "3101680139",
                new Period(LocalDate.parse("2026-03-01"),
                           LocalDate.parse("2026-03-31")),
                new Planilla("1051", "Planilla NeoProc"));

        PayrollSubmitResult result = new PayrollSubmitResult(
                PayrollSubmitResult.SCHEMA,
                envMeta(),
                resultTaskWithRequestFields,
                null,
                new SubmitResultBody(
                        "FAILED",
                        null, new SubmitResultBody.Totals("CRC", BigDecimal.ZERO, 0),
                        List.of(),
                        new RosterDiff(List.of(), List.of()),
                        new SubmitResultBody.ErrorDetail(
                                "agent-worker.PayrollTaskListener",
                                "UNCAUGHT_EXCEPTION",
                                "test"),
                        null),
                audit());

        assertThrows(SchemaValidationException.class,
                () -> SchemaValidator.validate(result, SchemaValidator.SUBMIT_RESULT),
                "result task block must reject period / planilla — those are request-side only");
    }

    @Test
    @DisplayName("Submit result task built from a request with period+planilla validates after wrapResult strips them")
    void submitPojo_listenerStripsPeriodAndPlanilla_validates() {
        // Mirror the post-fix PayrollTaskListener.wrapResult path: given a
        // request whose SubmitTask carries period + planilla (Praxis emits
        // these on every submit-request), the listener builds the result
        // task with SubmitTask.forSalaries(target, source, clientId, null, null)
        // — i.e. period + planilla stripped. This case is what the listener
        // actually emits on the wire; it MUST validate.
        SubmitTask requestTask = new SubmitTask(
                "PAYROLL_SUBMIT", "SUBMIT_SALARIES",
                "ccss-sicere", null, "3-101-680139",
                new Period(LocalDate.parse("2026-03-01"),
                           LocalDate.parse("2026-03-31")),
                new Planilla("1051", "Planilla NeoProc"));

        SubmitTask resultTask = SubmitTask.forSalaries(
                requestTask.targetPortal(),
                requestTask.sourceCaptureEnvelopeId(),
                requestTask.clientIdentifier(),
                null, null);

        PayrollSubmitResult result = new PayrollSubmitResult(
                PayrollSubmitResult.SCHEMA,
                envMeta(),
                resultTask,
                null,
                new SubmitResultBody(
                        "FAILED",
                        null, new SubmitResultBody.Totals("CRC", BigDecimal.ZERO, 0),
                        List.of(),
                        new RosterDiff(List.of(), List.of()),
                        new SubmitResultBody.ErrorDetail(
                                "agent-worker.PayrollTaskListener",
                                "CREDENTIALS_INVALID",
                                "Login rejected for client 3-101-680139"),
                        null),
                audit());

        SchemaValidator.validate(result, SchemaValidator.SUBMIT_RESULT);
    }

    @Test
    @DisplayName("Submit result with NAME_DRIFT signal validates")
    void submitPojo_nameDriftSignal_validates() {
        // Adapter emits NAME_DRIFT when cédula matches uniquely but
        // canonical and portal names diverge (e.g. AutoPlanilla typo).
        // The salary IS applied; the signal exists so HITL can confirm
        // identity before final-submit. status=PARTIAL with rosterDiff
        // empty is the expected envelope shape.
        SubmitResultBody.Review review = new SubmitResultBody.Review(
                "WARN",
                "1 name disagreement(s) — cédula matched, names differ (HITL review)",
                List.of(new SubmitResultBody.Signal(
                        "NAME_DRIFT",
                        null, null, null,
                        "207630807",
                        "EVELYN GODINES",                  // canonical
                        "GODINEZ BOZA EVELYN NATALIA",     // portalName
                        null, null)),
                List.of("FINAL_SUBMIT", "RESUBMIT", "ESCALATE"));

        PayrollSubmitResult result = new PayrollSubmitResult(
                PayrollSubmitResult.SCHEMA,
                envMeta(),
                SubmitTask.forSalaries("ccss-sicere", null,
                        "3-101-680139", null, null),
                null,
                new SubmitResultBody(
                        "PARTIAL",
                        null,
                        new SubmitResultBody.Totals(
                                "CRC",
                                new BigDecimal("18440999.90"),
                                13,
                                new BigDecimal("18440999.90"),
                                new BigDecimal("18440999.90")),
                        List.of(),
                        new RosterDiff(List.of(), List.of()),
                        null,
                        review),
                audit());

        SchemaValidator.validate(result, SchemaValidator.SUBMIT_RESULT);
    }

    @Test
    @DisplayName("Submit result with TOTAL_GAP / MISSING_FROM_PORTAL / MISSING_FROM_PAYROLL signals validates")
    void submitPojo_reviewSignalsWithBigDecimalFields_validates() {
        // Pre-fix this would fail with two distinct violations:
        //   1. signals[*].canonical/portal/gap serialized as JSON numbers,
        //      but the schema demands string-with-pattern-or-null.
        //   2. severity = "MISMATCH" / "PARTIAL" leaked through from the
        //      adapter's status arg, but the schema enum only allows
        //      INFO|WARN|ERROR.
        // Lock both in: build a Review with a TOTAL_GAP signal carrying
        // non-null BigDecimals plus full roster_diff signals, validate as
        // an actual MISMATCH envelope would be emitted on the wire.
        SubmitResultBody.Review review = new SubmitResultBody.Review(
                "ERROR",
                "Portal exceeds expected by 13870.00 CRC; "
                        + "1 employee(s) missing from portal, 1 missing from payroll",
                List.of(
                        new SubmitResultBody.Signal(
                                "TOTAL_GAP",
                                new BigDecimal("18440999.90"),
                                new BigDecimal("18454869.90"),
                                new BigDecimal("13870.00"),
                                null, null, null, null),
                        new SubmitResultBody.Signal(
                                "MISSING_FROM_PORTAL",
                                null, null, null,
                                "207630807", "EVELYN GODINES",
                                new BigDecimal("950000.00"), null),
                        new SubmitResultBody.Signal(
                                "MISSING_FROM_PAYROLL",
                                null, null, null,
                                "111110000", "JUAN PEREZ",
                                null, new BigDecimal("750000.00"))),
                List.of("FINAL_SUBMIT", "RESUBMIT", "ESCALATE"));

        PayrollSubmitResult result = new PayrollSubmitResult(
                PayrollSubmitResult.SCHEMA,
                envMeta(),
                SubmitTask.forSalaries("ccss-sicere", null,
                        "3-101-680139", null, null),
                null,
                new SubmitResultBody(
                        "MISMATCH",
                        null,
                        new SubmitResultBody.Totals(
                                "CRC",
                                new BigDecimal("18454869.90"),
                                15,
                                new BigDecimal("18440999.90"),
                                new BigDecimal("18454869.90")),
                        List.of(),
                        new RosterDiff(
                                List.of(new RosterDiff.MissingFromPortal(
                                        "207630807", "EVELYN GODINES",
                                        new BigDecimal("950000.00"), null)),
                                List.of(new RosterDiff.MissingFromPayroll(
                                        "111110000", "JUAN PEREZ",
                                        new BigDecimal("750000.00")))),
                        null,
                        review),
                audit());

        SchemaValidator.validate(result, SchemaValidator.SUBMIT_RESULT);
    }

    @Test
    @DisplayName("Review constructor rejects severity outside INFO/WARN/ERROR (status leak guard)")
    void reviewSeverityOutsideEnum_throwsAtConstruction() {
        // Mirror the regression: both adapters' buildReview previously
        // passed envelope status ("MISMATCH" / "PARTIAL") into the severity
        // arg. Both fields are String in Java so the wrong domain compiled
        // silently; the malformed envelope only surfaced at validate-on-
        // publish, with errorClass=PayrollTaskListener masking the real
        // origin. Lock the guard at the POJO layer: severity outside
        // {INFO,WARN,ERROR} fails fast at construction, in the buggy line
        // of code itself.
        assertThrows(IllegalArgumentException.class,
                () -> new SubmitResultBody.Review(
                        "MISMATCH",     // <-- envelope status, not a valid severity
                        "Portal exceeds expected by 1.00 CRC",
                        List.of(new SubmitResultBody.Signal(
                                "TOTAL_GAP",
                                new BigDecimal("100.00"),
                                new BigDecimal("101.00"),
                                new BigDecimal("1.00"),
                                null, null, null, null)),
                        List.of("FINAL_SUBMIT", "ESCALATE")),
                "Review.severity must be one of INFO|WARN|ERROR — never the envelope status");
    }

    @Test
    @DisplayName("Submit result with MISMATCH status uses §15.5 category vocabulary")
    void submitPojo_mismatchWithCategory_validates() {
        PayrollSubmitResult result = new PayrollSubmitResult(
                PayrollSubmitResult.SCHEMA,
                envMeta(),
                SubmitTask.forSalaries("ccss-sicere", null,
                        "3101680139", null, null),
                null,
                new SubmitResultBody(
                        "FAILED",
                        null,
                        new SubmitResultBody.Totals(
                                "CRC", BigDecimal.ZERO, 0,
                                new BigDecimal("18440999.90"), null),
                        List.of(),
                        new RosterDiff(List.of(), List.of()),
                        new SubmitResultBody.ErrorDetail(
                                "agent-worker.CcssSicereSubmitAdapter",
                                "CREDENTIALS_INVALID",
                                "Login rejected for client 3101680139"),
                        null),
                audit());

        SchemaValidator.validate(result, SchemaValidator.SUBMIT_RESULT);
    }

    private static EnvelopeMeta envMeta() {
        return new EnvelopeMeta(
                UUID.randomUUID().toString(),
                "smoke-test",
                1351L,
                "es",
                Instant.now(),
                "agent-worker/mock-payroll",
                "run-test");
    }

    private static Audit audit() {
        return new Audit("manifest.json", null, null,
                "0000000000000000000000000000000000000000000000000000000000000000");
    }
}
