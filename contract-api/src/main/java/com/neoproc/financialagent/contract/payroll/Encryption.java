package com.neoproc.financialagent.contract.payroll;

/**
 * Envelope encryption metadata. Present iff the matching payload field
 * ({@code result} on capture/submit-result; {@code request} on
 * submit-request) is a Vault-format ciphertext string instead of a
 * decoded body object.
 *
 * <p>Schemes:
 * <ul>
 *   <li>{@code vault-transit-v1} — production. Vault transit engine,
 *       firm-scoped key, REST encrypt/decrypt.</li>
 *   <li>{@code local-aes-gcm-v1} — development only. AES-GCM with a
 *       local key file. Same {@code vault:vN:...} ciphertext format so
 *       the envelope shape is identical across environments.</li>
 * </ul>
 */
public record Encryption(
        String scheme,
        String keyName,
        int keyVersion,
        String ciphertextField) {}
