package com.neoproc.financialagent.common.credentials;

/**
 * Source of portal credentials. The interface is intentionally tiny so that
 * a file-based dev implementation and an AWS Secrets Manager prod
 * implementation can swap without changing caller code. Placeholder
 * resolution in {@code PortalEngine} consumes the returned map directly.
 */
public interface CredentialsProvider {

    PortalCredentials get(String portalId);
}
