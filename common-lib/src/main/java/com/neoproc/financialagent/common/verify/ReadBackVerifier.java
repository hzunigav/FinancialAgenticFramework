package com.neoproc.financialagent.common.verify;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic Read-Back check per spec §7.2.
 *
 * <p>Given a source-of-truth record and a scraped record of the same type,
 * compare component-by-component and return per-field diffs. Record
 * components are compared via {@link Objects#equals}; no LLM involvement.
 */
public final class ReadBackVerifier {

    private ReadBackVerifier() {}

    public static <T extends Record> VerificationResult<T> verify(T source, T scraped) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(scraped, "scraped");
        if (!source.getClass().equals(scraped.getClass())) {
            throw new IllegalArgumentException(
                    "source and scraped must be the same record type: "
                            + source.getClass() + " vs " + scraped.getClass());
        }

        List<FieldDiff> diffs = new ArrayList<>();
        for (RecordComponent rc : source.getClass().getRecordComponents()) {
            Object s = invoke(rc, source);
            Object t = invoke(rc, scraped);
            if (!Objects.equals(s, t)) {
                diffs.add(new FieldDiff(rc.getName(), s, t));
            }
        }

        VerificationResult.Status status = diffs.isEmpty()
                ? VerificationResult.Status.MATCH
                : VerificationResult.Status.MISMATCH;
        return new VerificationResult<>(status, source, scraped, List.copyOf(diffs));
    }

    private static Object invoke(RecordComponent rc, Object record) {
        try {
            return rc.getAccessor().invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(
                    "Failed to read record component " + rc.getName(), e);
        }
    }
}
