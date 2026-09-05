package com.neoproc.financialagent.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filename construction for the CCSS report archive. These names are how the
 * documents get found later, so the pieces that build them are pinned here
 * rather than left to a live run to reveal.
 */
class CcssSicereReportsAdapterTest {

    @Test
    void companyTokenIsPascalCaseWithoutPunctuation() {
        assertEquals("NeoprocSociedadAnonima",
                CcssSicereReportsAdapter.toToken("NEOPROC SOCIEDAD ANONIMA"));
    }

    @Test
    void companyTokenFoldsAccentsSoFilenamesStayAscii() {
        // CCSS returns names with Spanish diacritics; a filename that keeps them
        // survives S3 but trips naive tooling downstream.
        assertEquals("PanaderiaNanduSA",
                CcssSicereReportsAdapter.toToken("Panadería Ñandú S.A."));
    }

    @Test
    void companyTokenIsCappedSoLongNamesDoNotDominate() {
        String token = CcssSicereReportsAdapter.toToken(
                "FEUJI COSTA RICA SOCIEDAD DE RESPONSABILIDAD LIMITADA");

        assertTrue(token.length() <= 40, "token should be capped, was " + token.length());
        assertTrue(token.startsWith("FeujiCostaRicaSociedad"), "prefix stays recognisable: " + token);
    }

    @Test
    void periodSuffixRendersMonthThenYear() {
        assertEquals("082026", CcssSicereReportsAdapter.periodSuffix("08/2026"));
        assertEquals("122025", CcssSicereReportsAdapter.periodSuffix(" 12/2025 "));
    }

    @Test
    void malformedPeriodIsObviousRatherThanGuessed() {
        // Filing a report under a plausible-but-wrong month is worse than an
        // obviously broken name, because nobody goes looking for it.
        assertEquals("000000", CcssSicereReportsAdapter.periodSuffix("2026-08"));
        assertEquals("000000", CcssSicereReportsAdapter.periodSuffix(""));
        assertEquals("000000", CcssSicereReportsAdapter.periodSuffix(null));
    }
}
