package com.neoproc.financialagent.worker.envelope;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neoproc.financialagent.common.crypto.CleartextCipher;
import com.neoproc.financialagent.common.crypto.EnvelopeCipher;
import com.neoproc.financialagent.common.crypto.KmsEnvelopeCipher;
import com.neoproc.financialagent.common.crypto.LocalDevCipher;
import com.neoproc.financialagent.common.crypto.VaultTransitCipher;
import com.neoproc.financialagent.contract.payroll.Encryption;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Read/write helpers for v1 payroll envelopes plus the cipher
 * factory the writer uses to encrypt the payload field.
 *
 * <p>Cipher selection is environment-driven:
 * <ul>
 *   <li>{@code FINANCEAGENT_CIPHER=kms} → {@link KmsEnvelopeCipher} (production, AWS KMS).</li>
 *   <li>{@code FINANCEAGENT_CIPHER=vault} → {@link VaultTransitCipher} (throws until wired).</li>
 *   <li>{@code FINANCEAGENT_CIPHER=cleartext} (alias: {@code none}) or <b>unset</b>
 *       → {@link CleartextCipher}: no encryption block; {@code result} is the plain
 *       body object. Wire-compatible with Praxis when running without KMS, since
 *       Praxis only handles cleartext or {@code kms-envelope-v1} on inbound results
 *       (PraxisIntegrationHandoff §A.3).</li>
 *   <li>{@code FINANCEAGENT_CIPHER=local} → {@link LocalDevCipher}, an opt-in
 *       <b>at-rest-only</b> scheme for run-dir artifacts. <b>Not wire-compatible
 *       with Praxis</b> — Praxis cannot decrypt {@code local-aes-gcm-v1}. Only
 *       use this for offline/CLI runs where there is no broker peer.</li>
 * </ul>
 */
public final class EnvelopeIo {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // Tolerate unknown fields on the wire — contracts evolve additively
            // and producers may include debug fields (e.g. __note_to_reader) in
            // dev fixtures. Per CONTRACT.md §8 backward-compat policy.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private EnvelopeIo() {}

    public static EnvelopeCipher defaultCipher() {
        String which = System.getenv("FINANCEAGENT_CIPHER");
        if ("kms".equalsIgnoreCase(which)) {
            return new KmsEnvelopeCipher();
        }
        if ("vault".equalsIgnoreCase(which)) {
            return new VaultTransitCipher();
        }
        if ("local".equalsIgnoreCase(which)) {
            return new LocalDevCipher();
        }
        // Cleartext is the safe default for any peer that is not the worker
        // itself: Praxis can read it, fixtures can read it, schema validation
        // accepts it. local-aes-gcm-v1 is opt-in via FINANCEAGENT_CIPHER=local
        // for offline/CLI runs that want at-rest encryption of run-dir artifacts.
        return new CleartextCipher();
    }

    /** Writes the envelope (cleartext or encrypted) to the given file as pretty JSON. */
    public static void write(Object envelope, Path file) {
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writeValue(file.toFile(), envelope);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write envelope to " + file, e);
        }
    }

    public static <T> T read(Path file, Class<T> envelopeType) {
        try {
            return MAPPER.readValue(file.toFile(), envelopeType);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read envelope from " + file, e);
        }
    }

    /**
     * Serialize the cleartext body, encrypt with the supplied cipher, and
     * return the encryption metadata + ciphertext string. The caller
     * substitutes the ciphertext into the envelope's {@code result} or
     * {@code request} field.
     */
    public static EncryptedPayload encryptBody(Object body, EnvelopeCipher cipher, long firmId) {
        String json = serialize(body);
        if ("none".equals(cipher.schemeName())) {
            return new EncryptedPayload(null, body, sha256Hex(json));
        }
        String keyName = "payroll-firm-" + firmId;
        String ciphertext = cipher.encrypt(json, keyName);
        int keyVersion = parseKeyVersion(ciphertext);
        Encryption meta = new Encryption(cipher.schemeName(), keyName, keyVersion, "result");
        return new EncryptedPayload(meta, ciphertext, sha256Hex(json));
    }

    /** Decrypts a ciphertext field and re-deserializes into {@code bodyType}. */
    public static <T> T decryptBody(String ciphertext,
                                    Encryption meta,
                                    EnvelopeCipher cipher,
                                    Class<T> bodyType) {
        if (!cipher.schemeName().equals(meta.scheme())) {
            throw new IllegalStateException(
                    "Cipher scheme mismatch: envelope=" + meta.scheme()
                            + " cipher=" + cipher.schemeName());
        }
        String json = cipher.decrypt(ciphertext, meta.keyName());
        try {
            return MAPPER.readValue(json, bodyType);
        } catch (IOException e) {
            throw new IllegalStateException("Decrypted payload is not valid " + bodyType.getSimpleName(), e);
        }
    }

    public static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String serialize(Object body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int parseKeyVersion(String vaultCiphertext) {
        // Format: vault:vN:base64...
        int vIdx = vaultCiphertext.indexOf(":v") + 2;
        int colon = vaultCiphertext.indexOf(':', vIdx);
        return Integer.parseInt(vaultCiphertext.substring(vIdx, colon));
    }

    public record EncryptedPayload(Encryption meta, Object result, String payloadSha256) {}
}
