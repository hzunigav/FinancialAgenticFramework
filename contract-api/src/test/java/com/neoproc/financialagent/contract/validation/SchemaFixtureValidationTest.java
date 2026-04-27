package com.neoproc.financialagent.contract.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives every {@code .valid-*.json} fixture under {@code src/test/resources/fixtures/}
 * through {@link SchemaValidator} and fails the build if any fixture stops
 * conforming. Each {@code .invalid-*.json} fixture must conversely fail.
 *
 * <p>The fixtures double as living examples of the wire format. When a
 * schema or producer changes shape, the failure here points at the exact
 * field that drifted — much earlier than catching it in an end-to-end run
 * with Praxis.
 *
 * <p>To extend: drop a new file under {@code fixtures/} named
 * {@code <schema-name>.valid-<case>.json} or {@code <schema-name>.invalid-<case>.json}
 * and add a {@code @CsvSource} row below.
 */
class SchemaFixtureValidationTest {

    @ParameterizedTest(name = "{0} matches {1}")
    @CsvSource({
        "fixtures/payroll-capture-request.valid.json,             payroll-capture-request.v1",
        "fixtures/payroll-capture-result.valid-encrypted.json,    payroll-capture-result.v1",
        "fixtures/payroll-capture-result.valid-cleartext.json,    payroll-capture-result.v1",
        "fixtures/payroll-submit-request.valid.json,              payroll-submit-request.v1",
        "fixtures/payroll-submit-result.valid-success.json,       payroll-submit-result.v1",
        "fixtures/payroll-submit-result.valid-failed.json,        payroll-submit-result.v1"
    })
    @DisplayName("Valid fixtures must validate against their declared schema")
    void validFixturesMustValidate(String fixturePath, String schemaName) throws IOException {
        SchemaValidator.validate(readFixture(fixturePath), schemaName);
    }

    @ParameterizedTest(name = "{0} must be rejected by {1}")
    @CsvSource({
        "fixtures/payroll-capture-result.invalid-bad-scheme.json, payroll-capture-result.v1"
    })
    @DisplayName("Invalid fixtures must be rejected with SchemaValidationException")
    void invalidFixturesMustBeRejected(String fixturePath, String schemaName) throws IOException {
        byte[] body = readFixture(fixturePath);
        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> SchemaValidator.validate(body, schemaName));
        assertTrue(ex.errors().stream().anyMatch(m -> m.toLowerCase().contains("scheme")
                                                   || m.toLowerCase().contains("enum")),
                "Expected a scheme/enum violation but got: " + ex.errors());
    }

    @Test
    @DisplayName("All four schemas load without parse errors")
    void allSchemasParse() {
        SchemaValidator.validate(minimalNoise(), SchemaValidator.CAPTURE_REQUEST);
    }

    private static byte[] readFixture(String path) throws IOException {
        try (InputStream is = SchemaFixtureValidationTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertNotNull(is, "Missing fixture: " + path);
            return is.readAllBytes();
        }
    }

    /** A capture-request payload that exists only to force the schema to load. */
    private static byte[] minimalNoise() {
        return ("""
                {
                  "schema": "payroll-capture-request.v1",
                  "envelope": {
                    "envelopeId": "00000000-0000-0000-0000-000000000000",
                    "businessKey": "x",
                    "firmId": 1,
                    "createdAt": "2026-01-01T00:00:00Z",
                    "issuer": "test",
                    "issuerRunId": "r"
                  },
                  "task": {
                    "type": "PAYROLL_CAPTURE",
                    "operation": "CAPTURE",
                    "sourcePortal": "mock-payroll"
                  }
                }
                """).getBytes();
    }
}
