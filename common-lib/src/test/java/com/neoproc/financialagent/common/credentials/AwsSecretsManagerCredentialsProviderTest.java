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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AwsSecretsManagerCredentialsProviderTest {

    @Mock SecretsManagerClient secretsManager;

    private static final String FIRM_ID = "42";
    private static final String PORTAL_ID = "autoplanilla";

    @BeforeEach
    void stubSecret() {
        when(secretsManager.getSecretValue(any(GetSecretValueRequest.class)))
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
}
