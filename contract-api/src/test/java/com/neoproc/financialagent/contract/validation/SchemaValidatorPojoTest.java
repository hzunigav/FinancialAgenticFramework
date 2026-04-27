package com.neoproc.financialagent.contract.validation;

import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.CaptureResultBody;
import com.neoproc.financialagent.contract.payroll.CaptureTask;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.payroll.PayrollCaptureResult;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitResult;
import com.neoproc.financialagent.contract.payroll.RosterDiff;
import com.neoproc.financialagent.contract.payroll.SubmitResultBody;
import com.neoproc.financialagent.contract.payroll.SubmitTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
