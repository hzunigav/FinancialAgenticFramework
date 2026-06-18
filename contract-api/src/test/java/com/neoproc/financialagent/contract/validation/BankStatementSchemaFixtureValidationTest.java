package com.neoproc.financialagent.contract.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the bank-statement wire fixtures against their schemas. The four
 * {@code valid-*} fixtures are the example envelopes shipped to the Praxis
 * team (request cleartext + encrypted, result success + failed); the
 * {@code invalid-*} fixtures lock two traps:
 * <ul>
 *   <li>monetary values emitted as JSON numbers (the schema demands decimal
 *       strings — float-drift / producer trap);</li>
 *   <li>{@code status=SUCCESS} carrying an {@code errorCategory} (the
 *       if/then/else invariant: error category is null iff SUCCESS).</li>
 * </ul>
 */
class BankStatementSchemaFixtureValidationTest {

    @ParameterizedTest(name = "{0} matches {1}")
    @CsvSource({
        "fixtures/bank-statement-upload-request.valid-cleartext.json, bank-statement-upload-request.v1",
        "fixtures/bank-statement-upload-request.valid-encrypted.json, bank-statement-upload-request.v1",
        "fixtures/bank-statement-upload-result.valid-success.json,    bank-statement-upload-result.v1",
        "fixtures/bank-statement-upload-result.valid-failed.json,     bank-statement-upload-result.v1"
    })
    @DisplayName("Valid bank-statement fixtures must validate against their declared schema")
    void validFixturesMustValidate(String fixturePath, String schemaName) throws IOException {
        SchemaValidator.validate(readFixture(fixturePath), schemaName);
    }

    @ParameterizedTest(name = "{0} must be rejected by {1}")
    @CsvSource({
        "fixtures/bank-statement-upload-request.invalid-money-as-number.json,   bank-statement-upload-request.v1",
        "fixtures/bank-statement-upload-result.invalid-success-with-error.json, bank-statement-upload-result.v1"
    })
    @DisplayName("Invalid bank-statement fixtures must be rejected with SchemaValidationException")
    void invalidFixturesMustBeRejected(String fixturePath, String schemaName) throws IOException {
        byte[] body = readFixture(fixturePath);
        assertThrows(SchemaValidationException.class,
                () -> SchemaValidator.validate(body, schemaName));
    }

    private static byte[] readFixture(String path) throws IOException {
        try (InputStream is = BankStatementSchemaFixtureValidationTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertNotNull(is, "Missing fixture: " + path);
            return is.readAllBytes();
        }
    }
}
