package com.neoproc.financialagent.common.crypto;

/**
 * Symmetric encryption boundary for payroll envelope payloads. Production
 * uses Vault transit; dev uses a local AES-GCM key. Both produce the
 * same {@code vault:vN:base64...} ciphertext shape so the envelope is
 * binary-identical across environments.
 *
 * <p>Per-firm keys: {@code keyName} resolves to the Vault transit key
 * (or local key id). Convention: {@code payroll-firm-<firmId>}.
 */
public interface EnvelopeCipher {

    /**
     * Encrypt {@code plaintext} under {@code keyName}. Returns a string
     * formatted as {@code vault:v<keyVersion>:<base64-ciphertext>}.
     */
    String encrypt(String plaintext, String keyName);

    /**
     * Decrypt a {@code vault:vN:...} ciphertext under {@code keyName}.
     * Implementations parse the version embedded in the ciphertext;
     * callers do not need to thread it explicitly.
     */
    String decrypt(String ciphertext, String keyName);

    /**
     * Identifier for the {@code Encryption.scheme} field of the envelope.
     * Matches the JSON Schema enum values.
     */
    String schemeName();
}
