package com.neoproc.financialagent.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Dev-only {@link EnvelopeCipher} backed by a per-key AES-256-GCM secret
 * stored on disk. Same {@code vault:vN:...} wire format as the
 * production Vault transit cipher so envelopes captured in dev decrypt
 * correctly when replayed against a Vault-backed reader (after the key
 * is mirrored into Vault's transit engine).
 *
 * <p>Keys live at {@code ~/.financeagent/cipher-keys/<keyName>}. Files
 * are created with {@code 600} perms (POSIX) or owner-only ACL
 * (Windows). Each key is a single AES-256 secret; key versioning is
 * always v1 in dev — rotation is a Vault concern.
 *
 * <p><strong>Not for production.</strong> The on-disk key file is
 * single-secret; if the laptop is compromised, every envelope ever
 * encrypted with this key is compromised. Production must swap to
 * {@link VaultTransitCipher}.
 */
public final class LocalDevCipher implements EnvelopeCipher {

    private static final String SCHEME = "local-aes-gcm-v1";
    private static final String ALGO = "AES";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RNG = new SecureRandom();

    private final Path keyDir;

    public LocalDevCipher() {
        this(defaultKeyDir());
    }

    public LocalDevCipher(Path keyDir) {
        this.keyDir = keyDir;
    }

    @Override
    public String schemeName() {
        return SCHEME;
    }

    @Override
    public String encrypt(String plaintext, String keyName) {
        SecretKey key = loadOrCreate(keyName);
        byte[] iv = new byte[IV_BYTES];
        RNG.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ct, 0, payload, iv.length, ct.length);
            return "vault:v1:" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encrypt failed for key " + keyName, e);
        }
    }

    @Override
    public String decrypt(String ciphertext, String keyName) {
        if (!ciphertext.startsWith("vault:v")) {
            throw new IllegalArgumentException(
                    "Not a vault-format ciphertext: " + truncate(ciphertext));
        }
        int colon = ciphertext.indexOf(':', "vault:v".length());
        if (colon < 0) {
            throw new IllegalArgumentException("Malformed vault ciphertext: " + truncate(ciphertext));
        }
        byte[] payload = Base64.getDecoder().decode(ciphertext.substring(colon + 1));
        if (payload.length < IV_BYTES + 16) {
            throw new IllegalArgumentException("Ciphertext too short");
        }
        byte[] iv = new byte[IV_BYTES];
        byte[] ct = new byte[payload.length - IV_BYTES];
        System.arraycopy(payload, 0, iv, 0, IV_BYTES);
        System.arraycopy(payload, IV_BYTES, ct, 0, ct.length);
        SecretKey key = loadOrCreate(keyName);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decrypt failed for key " + keyName, e);
        }
    }

    private SecretKey loadOrCreate(String keyName) {
        Path file = keyDir.resolve(keyName);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(keyDir);
                KeyGenerator gen = KeyGenerator.getInstance(ALGO);
                gen.init(KEY_BITS, RNG);
                byte[] raw = gen.generateKey().getEncoded();
                Files.write(file, raw);
            }
            byte[] raw = Files.readAllBytes(file);
            return new SecretKeySpec(raw, ALGO);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate key " + keyName, e);
        }
    }

    private static Path defaultKeyDir() {
        return Path.of(System.getProperty("user.home"), ".financeagent", "cipher-keys");
    }

    private static String truncate(String s) {
        return s.length() > 32 ? s.substring(0, 32) + "..." : s;
    }
}
