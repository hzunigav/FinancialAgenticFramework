package com.neoproc.financialagent.common.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

import javax.crypto.KeyGenerator;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KmsEnvelopeCipherTest {

    @Mock KmsClient kmsClient;

    private KmsEnvelopeCipher cipher;
    private byte[] fakeDek;

    @BeforeEach
    void setUp() throws Exception {
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256, new SecureRandom());
        fakeDek = gen.generateKey().getEncoded();

        // GenerateDataKey returns the plaintext DEK + a fake encrypted blob
        when(kmsClient.generateDataKey(any(GenerateDataKeyRequest.class)))
                .thenAnswer(inv -> GenerateDataKeyResponse.builder()
                        .plaintext(SdkBytes.fromByteArray(fakeDek))
                        .ciphertextBlob(SdkBytes.fromByteArray(new byte[]{0x01, 0x02})) // fake enc DEK
                        .build());

        // Decrypt returns the same plaintext DEK (simulates KMS round-trip)
        when(kmsClient.decrypt(any(DecryptRequest.class)))
                .thenAnswer(inv -> DecryptResponse.builder()
                        .plaintext(SdkBytes.fromByteArray(fakeDek))
                        .build());

        cipher = new KmsEnvelopeCipher(kmsClient);
    }

    @Test
    void schemeName() {
        assertEquals("kms-envelope-v1", cipher.schemeName());
    }

    @Test
    void roundTrip_utf8Payload() {
        String plaintext = "{\"totalGrossSalaries\":\"4500000.00\",\"name\":\"PABLO ROBERTO UREÑA GARCIA\"}";
        String ct = cipher.encrypt(plaintext, "payroll-firm-7");
        assertEquals(plaintext, cipher.decrypt(ct, "payroll-firm-7"));
    }

    @Test
    void wireFormat_prefix() {
        String ct = cipher.encrypt("hello", "payroll-firm-1");
        assertTrue(ct.startsWith("kms:v1:"), "must start with kms:v1:");
    }

    @Test
    void wireFormat_containsDotSeparator() {
        String ct = cipher.encrypt("hello", "payroll-firm-1");
        // kms:v1:<b64encDek>.<b64ivAndCt>
        String payload = ct.substring("kms:v1:".length());
        assertTrue(payload.contains("."), "payload must contain '.' separating encDEK from IV+CT");
    }

    @Test
    void encryptProducesUniqueIvEachCall() {
        String ct1 = cipher.encrypt("same payload", "payroll-firm-1");
        String ct2 = cipher.encrypt("same payload", "payroll-firm-1");
        assertNotEquals(ct1, ct2, "each encrypt call must use a fresh IV");
    }

    @Test
    void decrypt_rejectsNonKmsFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> cipher.decrypt("vault:v1:AAAA", "payroll-firm-1"));
    }

    @Test
    void decrypt_rejectsTruncatedPayload() {
        assertThrows(Exception.class,
                () -> cipher.decrypt("kms:v1:Ag==.Ag==", "payroll-firm-1"));
    }
}
