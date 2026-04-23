package com.neoproc.financialagent.common.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDevCipherTest {

    @Test
    void roundTrips_arbitraryUtf8(@TempDir Path tmp) {
        LocalDevCipher c = new LocalDevCipher(tmp);
        String pt = "{\"renta\":\"992025.46\",\"name\":\"PABLO ROBERTO UREÑA GARCIA\"}";
        String ct = c.encrypt(pt, "payroll-firm-12345");
        assertTrue(ct.startsWith("vault:v1:"), "wire format prefix");
        assertEquals(pt, c.decrypt(ct, "payroll-firm-12345"));
    }

    @Test
    void differentKeys_produceDifferentCiphertext_andCannotCrossDecrypt(@TempDir Path tmp) {
        LocalDevCipher c = new LocalDevCipher(tmp);
        String pt = "secret";
        String ctA = c.encrypt(pt, "payroll-firm-1");
        String ctB = c.encrypt(pt, "payroll-firm-2");
        assertNotEquals(ctA, ctB);
        assertThrows(IllegalStateException.class, () -> c.decrypt(ctA, "payroll-firm-2"));
    }

    @Test
    void sameKeyAndPlaintext_produceDifferentCiphertext_dueToRandomIv(@TempDir Path tmp) {
        LocalDevCipher c = new LocalDevCipher(tmp);
        String pt = "secret";
        String ct1 = c.encrypt(pt, "payroll-firm-1");
        String ct2 = c.encrypt(pt, "payroll-firm-1");
        assertNotEquals(ct1, ct2, "GCM with random IV must not produce the same ciphertext twice");
        assertEquals(pt, c.decrypt(ct1, "payroll-firm-1"));
        assertEquals(pt, c.decrypt(ct2, "payroll-firm-1"));
    }

    @Test
    void rejectsNonVaultFormat(@TempDir Path tmp) {
        LocalDevCipher c = new LocalDevCipher(tmp);
        assertThrows(IllegalArgumentException.class,
                () -> c.decrypt("not-a-vault-format", "payroll-firm-1"));
    }

    @Test
    void schemeName_isStableContractValue() {
        assertEquals("local-aes-gcm-v1", new LocalDevCipher().schemeName());
    }
}
