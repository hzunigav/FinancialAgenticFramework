package com.neoproc.financialagent.common.credentials;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AwsSecretsManagerCredentialsProviderTest {

    @Mock SecretsManagerClient secretsManager;

    private static final String FIRM_ID = "42";
    private static final String PORTAL_ID = "autoplanilla";

    @BeforeEach
    void stubSecret() {
        // lenient: tests that override this stub (or don't call secretsManager) won't trip UnnecessaryStubbingException
        lenient().when(secretsManager.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder()
                        .secretString("{\"username\":\"neoProc_user\",\"password\":\"s3cr3t\"}")
                        .build());
    }

    @Test
    void resolves_sharedPath() {
        var provider = new AwsSecretsManagerCredentialsProvider(
                FIRM_ID, id -> "shared", secretsManager);

        var captured = new String[1];
        when(secretsManager.getSecretValue(any(GetSecretValueRequest.class)))
                .thenAnswer(inv -> {
                    captured[0] = ((GetSecretValueRequest) inv.getArgument(0)).secretId();
                    return GetSecretValueResponse.builder()
                            .secretString("{\"username\":\"u\",\"password\":\"p\"}")
                            .build();
                });

        provider.get(PORTAL_ID);
        assertEquals("financeagent/shared/portals/" + PORTAL_ID, captured[0]);
    }

    @Test
    void resolves_perFirmPath() {
        var provider = new AwsSecretsManagerCredentialsProvider(
                FIRM_ID, id -> "per-firm", secretsManager);

        var captured = new String[1];
        when(secretsManager.getSecretValue(any(GetSecretValueRequest.class)))
                .thenAnswer(inv -> {
                    captured[0] = ((GetSecretValueRequest) inv.getArgument(0)).secretId();
                    return GetSecretValueResponse.builder()
                            .secretString("{\"username\":\"u\",\"password\":\"p\"}")
                            .build();
                });

        provider.get(PORTAL_ID);
        assertEquals("financeagent/firms/" + FIRM_ID + "/portals/" + PORTAL_ID, captured[0]);
    }

    @Test
    void parsesCredentialsFromJson() {
        var provider = new AwsSecretsManagerCredentialsProvider(
                FIRM_ID, id -> "shared", secretsManager);

        PortalCredentials creds = provider.get(PORTAL_ID);

        assertEquals(PORTAL_ID, creds.portalId());
        assertEquals("neoProc_user", creds.require("username"));
        assertEquals("s3cr3t", creds.require("password"));
    }

    @Test
    void throws_onBlankSecret() {
        when(secretsManager.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder().secretString("").build());

        var provider = new AwsSecretsManagerCredentialsProvider(
                FIRM_ID, id -> "shared", secretsManager);

        assertThrows(IllegalStateException.class, () -> provider.get(PORTAL_ID));
    }

    @Test
    void throws_onNonJsonSecret() {
        when(secretsManager.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder()
                        .secretString("not-json").build());

        var provider = new AwsSecretsManagerCredentialsProvider(
                FIRM_ID, id -> "shared", secretsManager);

        assertThrows(IllegalStateException.class, () -> provider.get(PORTAL_ID));
    }

    @Test
    void resolves_perClientPath() {
        var provider = new AwsSecretsManagerCredentialsProvider(
                FIRM_ID, id -> "per-client", secretsManager);

        var captured = new String[1];
        when(secretsManager.getSecretValue(any(GetSecretValueRequest.class)))
                .thenAnswer(inv -> {
                    captured[0] = ((GetSecretValueRequest) inv.getArgument(0)).secretId();
                    return GetSecretValueResponse.builder()
                            .secretString("{\"username\":\"u\",\"password\":\"p\"}")
                            .build();
                });

        provider.get("ccss-sicere", "3101680139");

        assertEquals(
                "financeagent/firms/" + FIRM_ID + "/portals/ccss-sicere/3101680139",
                captured[0]);
    }

    @Test
    void perClient_throws_whenClientIdMissing() {
        var provider = new AwsSecretsManagerCredentialsProvider(
                FIRM_ID, id -> "per-client", secretsManager);

        // Single-arg get(portalId) defaults clientId to null — per-client
        // scope must reject it loudly so the worker fails fast rather than
        // reading a nonsensical secret path.
        assertThrows(IllegalStateException.class, () -> provider.get("ccss-sicere"));
        assertThrows(IllegalStateException.class,
                () -> provider.get("ccss-sicere", ""));
        assertThrows(IllegalStateException.class,
                () -> provider.get("ccss-sicere", "  "));
    }

    @Test
    void perClient_stripsDashesFromCedulaJuridica() {
        // Praxis sends the legal cédula-jurídica form with dashes; the secret
        // path segment uses the dash-free internal id. The provider must
        // normalise so both forms hit the same secret.
        var provider = new AwsSecretsManagerCredentialsProvider(
                FIRM_ID, id -> "per-client", secretsManager);

        var captured = new String[1];
        when(secretsManager.getSecretValue(any(GetSecretValueRequest.class)))
                .thenAnswer(inv -> {
                    captured[0] = ((GetSecretValueRequest) inv.getArgument(0)).secretId();
                    return GetSecretValueResponse.builder()
                            .secretString("{\"username\":\"u\",\"password\":\"p\"}")
                            .build();
                });

        provider.get("ccss-sicere", "3-101-680139");

        assertEquals(
                "financeagent/firms/" + FIRM_ID + "/portals/ccss-sicere/3101680139",
                captured[0],
                "dashed form must resolve to the same secret as the digits-only form");
    }

    @Test
    void normalizeClientId_helperContract() {
        // The shared normaliser is the single source of truth for both
        // providers; lock its behaviour here so both stay aligned.
        assertEquals("3101680139",
                CredentialsProvider.normalizeClientId("3-101-680139"));
        assertEquals("3101680139",
                CredentialsProvider.normalizeClientId(" 3-101-680139 "));
        assertEquals("3101680139",
                CredentialsProvider.normalizeClientId("3101680139"));
        assertNull(CredentialsProvider.normalizeClientId(null));
        // Non-cédula identifiers (UUIDs, alphanumeric tenant ids) pass through
        // unchanged except for hyphen and whitespace stripping.
        assertEquals("abc123def",
                CredentialsProvider.normalizeClientId("abc-123-def"));
    }
}
