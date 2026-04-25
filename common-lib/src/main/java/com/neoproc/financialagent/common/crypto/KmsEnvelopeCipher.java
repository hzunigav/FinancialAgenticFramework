package com.neoproc.financialagent.common.crypto;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Production {@link EnvelopeCipher} backed by AWS KMS envelope encryption.
 *
 * <p>Per call to {@link #encrypt}, KMS generates a one-time 256-bit data key (DEK).
 * The DEK encrypts the payload locally via AES-256-GCM; only the KMS-encrypted DEK
 * travels in the ciphertext string — the plaintext DEK never leaves memory and is
 * zeroed immediately after use.
 *
 * <p>Wire format: {@code kms:v1:<base64(encryptedDEK)>.<base64(IV || ciphertext)>}
 * — the {@code kms:vN:} prefix keeps this parseable by the same version-extraction
 * logic used for the dev {@code vault:vN:} format in {@code EnvelopeIo}.
 *
 * <p>KMS key resolution: {@code keyName} is resolved as {@code alias/<keyName>}.
 * The caller follows the convention {@code payroll-firm-<firmId>} established by
 * {@code EnvelopeIo.encryptBody}.
 *
 * <p>Select this cipher at runtime with {@code FINANCEAGENT_CIPHER=kms}.
 */
public final class KmsEnvelopeCipher implements EnvelopeCipher {

    static final String SCHEME = "kms-envelope-v1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    private final KmsClient kms;

    public KmsEnvelopeCipher() {
        this(KmsClient.create());
    }

    KmsEnvelopeCipher(KmsClient kms) {
        this.kms = kms;
    }

    @Override
    public String schemeName() {
        return SCHEME;
    }

    @Override
    public String encrypt(String plaintext, String keyName) {
        var dkResp = kms.generateDataKey(r -> r
                .keyId("alias/" + keyName)
                .keySpec(DataKeySpec.AES_256));

        byte[] plaintextDek = dkResp.plaintext().asByteArray();
        byte[] encryptedDek = dkResp.ciphertextBlob().asByteArray();
        try {
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(plaintextDek, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] ivAndCt = new byte[IV_BYTES + ct.length];
            System.arraycopy(iv, 0, ivAndCt, 0, IV_BYTES);
            System.arraycopy(ct, 0, ivAndCt, IV_BYTES, ct.length);

            return "kms:v1:"
                    + Base64.getEncoder().encodeToString(encryptedDek)
                    + "."
                    + Base64.getEncoder().encodeToString(ivAndCt);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("KMS envelope encrypt failed for key " + keyName, e);
        } finally {
            Arrays.fill(plaintextDek, (byte) 0);
        }
    }

    @Override
    public String decrypt(String ciphertext, String keyName) {
        // kms:v1:<b64encDek>.<b64ivAndCt>
        int firstColon = ciphertext.indexOf(':');
        int secondColon = ciphertext.indexOf(':', firstColon + 1);
        if (firstColon < 0 || secondColon < 0 || !ciphertext.startsWith("kms:v")) {
            throw new IllegalArgumentException("Not a kms-format ciphertext: " + truncate(ciphertext));
        }
        String payload = ciphertext.substring(secondColon + 1);
        int dot = payload.indexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("Malformed kms ciphertext — missing DEK separator");
        }

        byte[] encryptedDek = Base64.getDecoder().decode(payload.substring(0, dot));
        byte[] ivAndCt = Base64.getDecoder().decode(payload.substring(dot + 1));

        var decResp = kms.decrypt(r -> r
                .ciphertextBlob(SdkBytes.fromByteArray(encryptedDek))
                .keyId("alias/" + keyName));

        byte[] plaintextDek = decResp.plaintext().asByteArray();
        try {
            if (ivAndCt.length < IV_BYTES + 16) {
                throw new IllegalArgumentException("KMS ciphertext payload too short");
            }
            byte[] iv = Arrays.copyOf(ivAndCt, IV_BYTES);
            byte[] ct = Arrays.copyOfRange(ivAndCt, IV_BYTES, ivAndCt.length);

            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(plaintextDek, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("KMS envelope decrypt failed for key " + keyName, e);
        } finally {
            Arrays.fill(plaintextDek, (byte) 0);
        }
    }

    private static String truncate(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}
