package com.neoproc.financialagent.common.verify;

import com.neoproc.financialagent.common.domain.ReportSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadBackVerifierTest {

    @Test
    void identicalRecords_match() {
        ReportSnapshot source = new ReportSnapshot("admin", LocalDate.of(2026, 4, 22));
        ReportSnapshot scraped = new ReportSnapshot("admin", LocalDate.of(2026, 4, 22));

        VerificationResult<ReportSnapshot> result = ReadBackVerifier.verify(source, scraped);

        assertTrue(result.matched());
        assertEquals(VerificationResult.Status.MATCH, result.status());
        assertTrue(result.diffs().isEmpty());
    }

    @Test
    void oneFieldDifferent_reportsOnlyThatField() {
        ReportSnapshot source = new ReportSnapshot("admin", LocalDate.of(2026, 4, 22));
        ReportSnapshot scraped = new ReportSnapshot("admin", LocalDate.of(2026, 4, 21));

        VerificationResult<ReportSnapshot> result = ReadBackVerifier.verify(source, scraped);

        assertFalse(result.matched());
        assertEquals(VerificationResult.Status.MISMATCH, result.status());
        assertEquals(1, result.diffs().size());

        FieldDiff diff = result.diffs().get(0);
        assertEquals("reportDate", diff.field());
        assertEquals(LocalDate.of(2026, 4, 22), diff.source());
        assertEquals(LocalDate.of(2026, 4, 21), diff.scraped());
    }

    @Test
    void everyFieldDifferent_reportsAllFields() {
        ReportSnapshot source = new ReportSnapshot("admin", LocalDate.of(2026, 4, 22));
        ReportSnapshot scraped = new ReportSnapshot("attacker", LocalDate.of(2026, 4, 21));

        VerificationResult<ReportSnapshot> result = ReadBackVerifier.verify(source, scraped);

        assertFalse(result.matched());
        assertEquals(2, result.diffs().size());
        assertTrue(result.diffs().stream().anyMatch(d -> d.field().equals("username")));
        assertTrue(result.diffs().stream().anyMatch(d -> d.field().equals("reportDate")));
    }

    @Test
    void differentRecordTypes_rejected() {
        record Other(String username, LocalDate reportDate) {}

        ReportSnapshot source = new ReportSnapshot("admin", LocalDate.of(2026, 4, 22));
        Other scraped = new Other("admin", LocalDate.of(2026, 4, 22));

        assertThrows(IllegalArgumentException.class,
                () -> ReadBackVerifier.verify((Record) source, (Record) scraped));
    }

    @Test
    void nullArguments_rejected() {
        ReportSnapshot snap = new ReportSnapshot("admin", LocalDate.of(2026, 4, 22));
        assertThrows(NullPointerException.class, () -> ReadBackVerifier.verify(null, snap));
        assertThrows(NullPointerException.class, () -> ReadBackVerifier.verify(snap, null));
    }
}
