package com.neoproc.financialagent.contract.validation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown by {@link SchemaValidator} when a payload does not conform to its
 * declared envelope schema.
 *
 * <p>Carries the schema name and the list of human-readable validation error
 * messages produced by the underlying networknt validator. The
 * {@link #getMessage()} format is intentionally compact so it shows up
 * cleanly in a single log line and in DLQ rejection reasons.
 */
public class SchemaValidationException extends RuntimeException {

    private final String schemaName;
    private final List<String> errors;

    public SchemaValidationException(String schemaName, List<String> errors) {
        super(formatMessage(schemaName, errors));
        this.schemaName = schemaName;
        this.errors = List.copyOf(errors);
    }

    public String schemaName() { return schemaName; }
    public List<String> errors() { return errors; }

    private static String formatMessage(String schemaName, List<String> errors) {
        return "Schema validation failed for " + schemaName + " ("
                + errors.size() + " errors): "
                + errors.stream().limit(5).collect(Collectors.joining("; "))
                + (errors.size() > 5 ? "; ..." : "");
    }
}
