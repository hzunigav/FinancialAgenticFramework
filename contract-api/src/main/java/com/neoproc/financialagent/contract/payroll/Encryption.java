package com.neoproc.financialagent.contract.payroll;

/**
 * Envelope encryption metadata. Present iff the matching payload field
 * ({@code result} on capture/submit-result; {@code request} on
 * submit-request) is a Vault-format ciphertext string instead of a
 * decoded body object.
 *
 * <p>Schemes (must match the JSON Schema {@code Encryption.scheme} enum):
 * <ul>
 *   <li>{@code kms-envelope-v1} — production. AWS KMS data-key envelope
 *       encryption; ciphertext wire format is {@code vault:v1:base64}.</li>
 *   <li>{@code vault-transit-v1} — legacy / future Vault transit engine.</li>
 * </ul>
 *
 * <p>Dev / staging mode uses cleartext: no {@code Encryption} block is
 * emitted and the {@code result} field carries the plain body object.
 * Activated via {@code FINANCEAGENT_CIPHER=none} on the agent-worker.
 */
public record Encryption(
        String scheme,
        String keyName,
        int keyVersion,
        String ciphertextField) {}
