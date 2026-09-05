package com.neoproc.financialagent.common.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalEncryptedSessionStoreTest {

    @Test
    void saveThenLoad_roundTripsPlaintext(@TempDir Path dir) {
        LocalEncryptedSessionStore store = new LocalEncryptedSessionStore(
                dir.resolve("sessions"), dir.resolve("key"));
        String payload = "{\"cookies\":[{\"name\":\"SESSION\",\"value\":\"abc\"}]}";

        store.save("prod-payroll", payload);
        Optional<String> loaded = store.load("prod-payroll", Duration.ofMinutes(30));

        assertTrue(loaded.isPresent());
        assertEquals(payload, loaded.get());
    }

    @Test
    void savedFile_doesNotContainPlaintext(@TempDir Path dir) throws Exception {
        LocalEncryptedSessionStore store = new LocalEncryptedSessionStore(
                dir.resolve("sessions"), dir.resolve("key"));
        String secret = "cookie-value-that-should-not-appear-on-disk";

        store.save("portal", secret);

        byte[] bytes = Files.readAllBytes(dir.resolve("sessions/portal.enc"));
        String asText = new String(bytes);
        assertFalse(asText.contains(secret),
                "plaintext secret leaked into encrypted file");
    }

    @Test
    void expiredSession_returnsEmpty_andPurgesFile(@TempDir Path dir) throws Exception {
        Path sessions = dir.resolve("sessions");
        LocalEncryptedSessionStore store = new LocalEncryptedSessionStore(
                sessions, dir.resolve("key"));
        store.save("portal", "payload");

        // Zero-duration maxAge makes the saved record older than allowed.
        Optional<String> loaded = store.load("portal", Duration.ZERO);

        assertTrue(loaded.isEmpty());
        assertFalse(Files.exists(sessions.resolve("portal.enc")),
                "expired session file should have been purged on load");
    }

    @Test
    void zeroMaxAgeNeverReuses_evenWhenSaveAndLoadShareAClockTick(@TempDir Path dir) {
        // ttlMinutes: 0 in a descriptor means "always log in fresh". While the
        // expiry check used a strict >, that promise held only if the clock
        // ticked between save and load — so this passed or failed depending on
        // machine speed (~1 run in 4 failed on a warm JVM). Looping pins the
        // boundary regardless of clock granularity.
        LocalEncryptedSessionStore store = new LocalEncryptedSessionStore(
                dir.resolve("sessions"), dir.resolve("key"));

        for (int i = 0; i < 200; i++) {
            store.save("portal", "payload");
            assertTrue(store.load("portal", Duration.ZERO).isEmpty(),
                    "a zero TTL must never hand back a session, attempt " + i);
        }
    }

    @Test
    void sessionWithinMaxAgeIsStillReturned(@TempDir Path dir) {
        // Guards the other side of the boundary: tightening > to >= must not
        // start expiring sessions that are still comfortably inside their TTL.
        LocalEncryptedSessionStore store = new LocalEncryptedSessionStore(
                dir.resolve("sessions"), dir.resolve("key"));
        store.save("portal", "payload");

        assertEquals(Optional.of("payload"), store.load("portal", Duration.ofHours(1)));
    }

    @Test
    void missingSession_returnsEmpty(@TempDir Path dir) {
        LocalEncryptedSessionStore store = new LocalEncryptedSessionStore(
                dir.resolve("sessions"), dir.resolve("key"));

        assertTrue(store.load("never-saved", Duration.ofHours(1)).isEmpty());
    }

    @Test
    void purge_removesFile(@TempDir Path dir) {
        Path sessions = dir.resolve("sessions");
        LocalEncryptedSessionStore store = new LocalEncryptedSessionStore(
                sessions, dir.resolve("key"));
        store.save("portal", "payload");
        assertTrue(Files.exists(sessions.resolve("portal.enc")));

        store.purge("portal");

        assertFalse(Files.exists(sessions.resolve("portal.enc")));
    }

    @Test
    void differentPortals_keepSeparateFiles(@TempDir Path dir) {
        LocalEncryptedSessionStore store = new LocalEncryptedSessionStore(
                dir.resolve("sessions"), dir.resolve("key"));
        store.save("portal-a", "payload-a");
        store.save("portal-b", "payload-b");

        assertEquals("payload-a", store.load("portal-a", Duration.ofHours(1)).orElseThrow());
        assertEquals("payload-b", store.load("portal-b", Duration.ofHours(1)).orElseThrow());
    }

    @Test
    void sameKeyReusedAcrossSaves(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("key");
        LocalEncryptedSessionStore store = new LocalEncryptedSessionStore(
                dir.resolve("sessions"), keyFile);
        store.save("portal", "first");
        byte[] keyAfterFirst = Files.readAllBytes(keyFile);
        store.save("portal", "second");
        byte[] keyAfterSecond = Files.readAllBytes(keyFile);

        assertArrayEquals(keyAfterFirst, keyAfterSecond,
                "key file should not be rewritten on subsequent saves");
    }

    @Test
    void twoSavesOfSamePayload_produceDifferentCiphertext(@TempDir Path dir) throws Exception {
        Path sessions = dir.resolve("sessions");
        LocalEncryptedSessionStore store = new LocalEncryptedSessionStore(
                sessions, dir.resolve("key"));
        store.save("portal", "same-payload");
        byte[] first = Files.readAllBytes(sessions.resolve("portal.enc"));
        store.save("portal", "same-payload");
        byte[] second = Files.readAllBytes(sessions.resolve("portal.enc"));

        assertNotEquals(new String(first), new String(second),
                "IV should be random, so ciphertext bytes should differ");
    }
}
