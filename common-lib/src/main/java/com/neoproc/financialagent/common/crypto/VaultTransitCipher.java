package com.neoproc.financialagent.common.crypto;

/**
 * Production {@link EnvelopeCipher} backed by HashiCorp Vault's transit
 * secrets engine. <strong>Stub at this milestone</strong> — Praxis has
 * not yet mounted the transit engine or added a Vault Java client (see
 * CONTRACT.md §"Praxis-side runbook"). When the integration lands, this
 * class becomes a thin wrapper around {@code POST /v1/transit/encrypt/<keyName>}
 * and {@code POST /v1/transit/decrypt/<keyName>} using whatever Vault
 * client Praxis selects (Spring Cloud Vault or plain WebClient).
 *
 * <p>Throws {@link UnsupportedOperationException} until then so that
 * accidentally selecting this cipher in dev is a loud failure rather
 * than a silent no-op.
 */
public final class VaultTransitCipher implements EnvelopeCipher {

    @Override
    public String schemeName() {
        return "vault-transit-v1";
    }

    @Override
    public String encrypt(String plaintext, String keyName) {
        throw new UnsupportedOperationException(
                "VaultTransitCipher is not wired yet — see CONTRACT.md for the Praxis-side runbook. "
                        + "For local dev set FINANCEAGENT_CIPHER=local in the environment.");
    }

    @Override
    public String decrypt(String ciphertext, String keyName) {
        throw new UnsupportedOperationException(
                "VaultTransitCipher is not wired yet — see CONTRACT.md for the Praxis-side runbook.");
    }
}
