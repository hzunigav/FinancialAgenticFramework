package com.neoproc.financialagent.contract.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JSON Schema 2020-12 validator for payroll envelopes. Validates either a
 * Java POJO (re-serialized through Jackson) or raw JSON bytes against a
 * named schema under {@code /schemas/v1/}.
 *
 * <p>Cross-schema {@code $ref}s using the canonical
 * {@code https://neoproc.com/financialagent/schemas/} prefix are resolved
 * against the same classpath directory. Loaded schemas are cached.
 *
 * <p>Use this on both sides of the wire:
 * <ul>
 *   <li><b>Producer</b> — call before publishing to RabbitMQ; failure is a
 *       bug in the producer and should fail fast with a FAILED result envelope
 *       carrying category {@code SCHEMA_VIOLATION}.</li>
 *   <li><b>Consumer</b> — call after deserialization to defend against bad
 *       envelopes from upstream; failure should DLQ via the listener error
 *       handler so the bad message is grep-able and replayable.</li>
 * </ul>
 */
public final class SchemaValidator {

    public static final String CAPTURE_REQUEST = "payroll-capture-request.v1";
    public static final String CAPTURE_RESULT  = "payroll-capture-result.v1";
    public static final String SUBMIT_REQUEST  = "payroll-submit-request.v1";
    public static final String SUBMIT_RESULT   = "payroll-submit-result.v1";

    private static final String CLASSPATH_PREFIX = "/schemas/v1/";
    private static final String CANONICAL_URI_PREFIX =
            "https://neoproc.com/financialagent/schemas/";

    // Must mirror EnvelopeIo.MAPPER's date handling: when validating a POJO
    // (validate(Object,...)), Jackson's default would write Instant as a
    // numeric timestamp (epoch seconds + nanos), failing the schema's
    // "format: date-time" expectation that envelope.createdAt is an ISO
    // string. The on-disk envelopes from EnvelopeIo are correct ISO; this
    // mapper must match or validate-on-publish rejects every result.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory
            .builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
            .schemaMappers(m -> m.mapPrefix(CANONICAL_URI_PREFIX, "classpath:" + CLASSPATH_PREFIX))
            .build();

    private static final ConcurrentMap<String, JsonSchema> CACHE = new ConcurrentHashMap<>();

    private SchemaValidator() {}

    /**
     * Validate a Java envelope POJO. The object is serialized via Jackson
     * (using the same {@link com.fasterxml.jackson.datatype.jsr310.JavaTimeModule}
     * the rest of the pipeline uses) and validated against the named schema.
     */
    public static void validate(Object envelope, String schemaName) {
        JsonNode node = MAPPER.valueToTree(envelope);
        validateNode(node, schemaName);
    }

    /**
     * Validate raw JSON bytes — preferred on the consumer side because it
     * preserves any fields that lenient deserialization would have dropped
     * (e.g. {@code FAIL_ON_UNKNOWN_PROPERTIES=false}). Catches
     * {@code additionalProperties: false} violations end-to-end.
     */
    public static void validate(byte[] jsonBytes, String schemaName) {
        try {
            JsonNode node = MAPPER.readTree(jsonBytes);
            validateNode(node, schemaName);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not parse JSON for schema " + schemaName, e);
        }
    }

    private static void validateNode(JsonNode node, String schemaName) {
        JsonSchema schema = loadSchema(schemaName);
        Set<ValidationMessage> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            List<String> messages = errors.stream().map(ValidationMessage::getMessage).toList();
            throw new SchemaValidationException(schemaName, messages);
        }
    }

    private static JsonSchema loadSchema(String schemaName) {
        return CACHE.computeIfAbsent(schemaName, name -> {
            String resourcePath = CLASSPATH_PREFIX + name + ".json";
            try (InputStream is = SchemaValidator.class.getResourceAsStream(resourcePath)) {
                if (is == null) {
                    throw new IllegalArgumentException(
                            "Schema not found on classpath: " + resourcePath);
                }
                return FACTORY.getSchema(is);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed loading schema " + resourcePath, e);
            }
        });
    }

}
