package com.neoproc.financialagent.common.crypto;

/**
 * No-op cipher for dev/test environments where Praxis runs with
 * {@code praxis.payroll.encryption.enabled=false}. Signals the envelope
 * writer to skip encryption: {@code encryption} block is omitted from the
 * output and {@code result} carries the plain body object.
 *
 * <p>Neither {@link #encrypt} nor {@link #decrypt} should be called;
 * callers branch on {@code "none".equals(cipher.schemeName())} before
 * invoking those methods.
 *
 * <p>Activated via {@code FINANCEAGENT_CIPHER=none}.
 */
public final class CleartextCipher implements EnvelopeCipher {

    @Override
    public String schemeName() {
        return "none";
    }

    @Override
    public String encrypt(String plaintext, String keyName) {
        throw new UnsupportedOperationException(
                "CleartextCipher does not encrypt — check schemeName() before calling encrypt()");
    }

    @Override
    public String decrypt(String ciphertext, String keyName) {
        throw new UnsupportedOperationException(
                "CleartextCipher does not decrypt — check schemeName() before calling decrypt()");
    }
}
