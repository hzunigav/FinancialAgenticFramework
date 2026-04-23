package com.neoproc.financialagent.common.credentials;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Dev-only {@link CredentialsProvider} backed by a plain properties file
 * located outside the repository. Resolution order:
 * <ol>
 *   <li>{@code FINANCEAGENT_SECRETS_FILE} env var, if set.</li>
 *   <li>{@code ~/.financeagent/secrets.properties}.</li>
 * </ol>
 *
 * <p>Keys are namespaced per portal:
 * {@code portals.<portalId>.credentials.<name>=<value>}. The provider
 * strips the {@code portals.<portalId>.} prefix so consumers see the same
 * flat map whatever the backing store.
 *
 * <p>Enforces that the file is not group- or world-readable. On POSIX the
 * check is strict (refuses to load); on Windows, which does not expose
 * POSIX perms, it inspects the ACL and rejects the load if any principal
 * other than the file owner has read access.
 */
public final class LocalFileCredentialsProvider implements CredentialsProvider {

    static final String ENV_OVERRIDE = "FINANCEAGENT_SECRETS_FILE";
    static final String DEFAULT_RELATIVE = ".financeagent/secrets.properties";

    private static final String KEY_PREFIX = "portals.";
    private static final String CRED_INFIX = ".credentials.";

    private final Path secretsFile;

    public LocalFileCredentialsProvider() {
        this(resolveDefaultPath());
    }

    public LocalFileCredentialsProvider(Path secretsFile) {
        this.secretsFile = secretsFile;
    }

    @Override
    public PortalCredentials get(String portalId) {
        if (!Files.isRegularFile(secretsFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "Credentials file not found: " + secretsFile
                            + " (set " + ENV_OVERRIDE + " or create ~/" + DEFAULT_RELATIVE + ")");
        }

        assertNotBroadlyReadable(secretsFile);

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(secretsFile)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + secretsFile, e);
        }

        String credPrefix = KEY_PREFIX + portalId + CRED_INFIX;
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(credPrefix)) {
                values.put(key.substring(credPrefix.length()), props.getProperty(key));
            }
        }
        if (values.isEmpty()) {
            throw new IllegalStateException(
                    "No credentials found for portal '" + portalId + "' in " + secretsFile
                            + " (expected keys starting with " + credPrefix + ")");
        }
        return new PortalCredentials(portalId, Map.copyOf(values));
    }

    private static Path resolveDefaultPath() {
        String override = System.getenv(ENV_OVERRIDE);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        String home = System.getProperty("user.home");
        return Paths.get(home, DEFAULT_RELATIVE);
    }

    private static void assertNotBroadlyReadable(Path file) {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            assertNotBroadlyReadablePosix(file, posix);
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            assertNotBroadlyReadableAcl(file, acl);
            return;
        }
        // Filesystem supports neither view; refuse rather than assume safety.
        throw new IllegalStateException(
                "Cannot verify permissions of " + file
                        + " (filesystem exposes neither POSIX perms nor ACL).");
    }

    private static void assertNotBroadlyReadablePosix(Path file, PosixFileAttributeView view) {
        try {
            Set<PosixFilePermission> perms = view.readAttributes().permissions();
            if (perms.contains(PosixFilePermission.GROUP_READ)
                    || perms.contains(PosixFilePermission.OTHERS_READ)) {
                throw new SecurityException(
                        "Credentials file " + file + " must not be group- or world-readable. "
                                + "Run: chmod 600 " + file);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read POSIX perms for " + file, e);
        }
    }

    private static void assertNotBroadlyReadableAcl(Path file, AclFileAttributeView view) {
        try {
            UserPrincipal owner = Files.getOwner(file);
            for (AclEntry entry : view.getAcl()) {
                if (entry.principal().equals(owner)) continue;
                if (isPrivilegedSystemPrincipal(entry.principal().getName())) continue;
                if (entry.permissions().contains(AclEntryPermission.READ_DATA)) {
                    throw new SecurityException(
                            "Credentials file " + file + " grants read access to "
                                    + entry.principal().getName()
                                    + "; restrict to the file owner only.");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read ACL for " + file, e);
        }
    }

    /**
     * Windows grants SYSTEM and Administrators access to everything in a
     * user's profile by default. These are not "other users" in a threat
     * sense — on a single-user machine SYSTEM is effectively the kernel,
     * and an admin already has full-disk access. Our check is about
     * blocking other real users and accidental cloud-sync leaks, not
     * fighting the platform.
     */
    private static boolean isPrivilegedSystemPrincipal(String name) {
        return name.equalsIgnoreCase("NT AUTHORITY\\SYSTEM")
                || name.equalsIgnoreCase("BUILTIN\\Administrators")
                || name.equalsIgnoreCase("NT AUTHORITY\\LOCAL SERVICE")
                || name.equalsIgnoreCase("NT AUTHORITY\\NETWORK SERVICE");
    }
}
