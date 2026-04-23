package com.neoproc.financialagent.common.match;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmployeeMatcherTest {

    @Test
    void normalizeId_stripsHyphensSpacesLetters() {
        assertEquals("109090501", EmployeeMatcher.normalizeId("1-0909-0501"));
        assertEquals("109090501", EmployeeMatcher.normalizeId("  1 0909 0501 "));
        assertEquals("109090501", EmployeeMatcher.normalizeId("ID:1-0909-0501"));
        assertEquals("109090501", EmployeeMatcher.normalizeId("109090501"));
    }

    @Test
    void normalizeId_emptyAndNull() {
        assertEquals("", EmployeeMatcher.normalizeId(""));
        assertEquals("", EmployeeMatcher.normalizeId(null));
        assertEquals("", EmployeeMatcher.normalizeId("---"));
    }

    @Test
    void normalizeName_foldsAccentsAndCase() {
        assertEquals("maria nunez", EmployeeMatcher.normalizeName("María Núñez"));
        assertEquals("jose solis", EmployeeMatcher.normalizeName("  José  Solís  "));
        assertEquals("sofia jimenez alfaro",
                EmployeeMatcher.normalizeName("Sofía Jiménez Alfaro"));
    }

    @Test
    void matchById_findsRegardlessOfFormatting() {
        List<String> rows = List.of("1-0909-0501", "1-1234-5678", "3-0456-7890");
        assertEquals(Optional.of(0), EmployeeMatcher.matchById("109090501", rows));
        assertEquals(Optional.of(1), EmployeeMatcher.matchById("1-1234-5678", rows));
        assertEquals(Optional.of(2), EmployeeMatcher.matchById("3 0456 7890", rows));
        assertEquals(Optional.empty(), EmployeeMatcher.matchById("999999999", rows));
    }

    @Test
    void matchById_emptyInputs() {
        assertEquals(Optional.empty(), EmployeeMatcher.matchById("", List.of("1-2-3")));
        assertEquals(Optional.empty(), EmployeeMatcher.matchById(null, List.of("1-2-3")));
        assertEquals(Optional.empty(), EmployeeMatcher.matchById("123", List.of()));
    }

    @Test
    void confirmName_exactMatch() {
        assertTrue(EmployeeMatcher.confirmName("María Fernández", "María Fernández"));
    }

    @Test
    void confirmName_accentTransliterated() {
        // portal renders ñ as n, é as e
        assertTrue(EmployeeMatcher.confirmName("José Núñez", "Jose Nunez"));
        assertTrue(EmployeeMatcher.confirmName("Carlos Peña Castro", "Carlos Pena Castro"));
    }

    @Test
    void confirmName_canonicalShorterThanDisplayed() {
        // agent knows "María F", portal shows full name → accept
        assertTrue(EmployeeMatcher.confirmName("María F", "María Fernández González"));
        assertTrue(EmployeeMatcher.confirmName("María", "María Fernández González"));
    }

    @Test
    void confirmName_displayedShorterThanCanonical() {
        // portal truncates to just first name; canonical is full → still accept
        assertTrue(EmployeeMatcher.confirmName("María Fernández González", "María"));
    }

    @Test
    void confirmName_tokenMustAppearInLonger() {
        // "Rodrigo Vargas" would NOT be a plausible match for "Pedro Solís"
        assertFalse(EmployeeMatcher.confirmName("Pedro Solís", "Rodrigo Vargas"));
    }

    @Test
    void confirmName_emptyRejects() {
        assertFalse(EmployeeMatcher.confirmName("", "Anything"));
        assertFalse(EmployeeMatcher.confirmName("Something", ""));
        assertFalse(EmployeeMatcher.confirmName(null, "Anything"));
    }

    @Test
    void match_requiresBothIdAndName() {
        List<String> ids = List.of("1-0909-0501", "1-1234-5678");
        List<String> names = List.of("María Fernández", "José Núñez");

        // ID matches and name plausible → accept
        assertEquals(Optional.of(0),
                EmployeeMatcher.match("109090501", "Maria F", ids, names));
        // ID matches and name is transliterated → accept
        assertEquals(Optional.of(1),
                EmployeeMatcher.match("112345678", "Jose Nunez", ids, names));
        // ID matches but name is obviously wrong → reject (data drift guard)
        assertEquals(Optional.empty(),
                EmployeeMatcher.match("109090501", "Carlos Vargas", ids, names));
        // ID not found → reject
        assertEquals(Optional.empty(),
                EmployeeMatcher.match("999999999", "Maria Fernandez", ids, names));
    }

    @Test
    void match_rejectsMismatchedInputLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> EmployeeMatcher.match("1", "A",
                        List.of("1", "2"), List.of("A")));
    }
}
