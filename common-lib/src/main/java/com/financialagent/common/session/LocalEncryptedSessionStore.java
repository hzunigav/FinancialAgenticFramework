package com.financialagent.common.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/**
 * AES-256-GCM encrypted session store rooted at
 * {@code ~/.financeagent/sessions/}. The symmetric key is generated once
 * on first use and stored at {@code ~/.financeagent/session-key}; both
 * files must be restricted to the owner. In-scope threat model: accidental
 * backup/cloud-sync leaks and offline disk access. Not in scope: local
 * malware already running as the same user (it would have the key too) —
 * that's addressed at M6 by moving sessions into Secrets Manager + KMS.
 */
public final class LocalEncryptedSessionStore implements SessionStore {

    static final String DEFAULT_DIR = ".financeagent/sessions";
    static final String DEFAULT_KEY = ".financeagent/session-key";

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final Path sessionsDir;
    private final Path keyFile;

    public LocalEncryptedSessionStore() {
        String home = System.getProperty("user.home");
        this.sessionsDir = Paths.get(home, DEFAULT_DIR);
        this.keyFile = Paths.get(home, DEFAULT_KEY);
    }

    public LocalEncryptedSessionStore(Path sessionsDir, Path keyFile) {
        this.sessionsDir = sessionsDir;
        this.keyFile = keyFile;
    }

    @Override
    public Optional<String> load(String portalId, Duration maxAge) {
        Path file = sessionsDir.resolve(portalId + ".enc");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Envelope env = JSON.readValue(file.toFile(), Envelope.class);
            if (Duration.between(env.savedAt, Instant.now()).compareTo(maxAge) > 0) {
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            byte[] plaintext = decrypt(loadOrCreateKey(), env);
            return Optional.of(new String(plaintext, StandardCharsets.UTF_8));
        } catch (IOException | GeneralSecurityExceptionWrapper e) {
            // corrupt or unreadable — purge and force re-auth
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            return Optional.empty();
        }
    }

    @Override
    public void save(String portalId, String sessionJson) {
        try {
            Files.createDirectories(sessionsDir);
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            byte[] ciphertext = encrypt(loadOrCreateKey(), iv,
                    sessionJson.getBytes(StandardCharsets.UTF_8));
            Envelope env = new Envelope(
                    Instant.now(),
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(ciphertext));
            Path file = sessionsDir.resolve(portalId + ".enc");
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), env);
            restrictToOwner(file);
        } catch (IOException | GeneralSecurityExceptionWrapper e) {
            throw new IllegalStateException("Failed to save session for " + portalId, e);
        }
    }

    @Override
    public void purge(String portalId) {
        Path file = sessionsDir.resolve(portalId + ".enc");
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
    }

    private SecretKey loadOrCreateKey() {
        try {
            if (!Files.isRegularFile(keyFile)) {
                KeyGenerator gen = KeyGenerator.getInstance("AES");
                gen.init(KEY_BITS, RANDOM);
                byte[] raw = gen.generateKey().getEncoded();
                Files.createDirectories(keyFile.getParent());
                Files.write(keyFile, Base64.getEncoder().encode(raw));
                restrictToOwner(keyFile);
                return new SecretKeySpec(raw, "AES");
            }
            assertOwnerOnly(keyFile);
            byte[] raw = Base64.getDecoder().decode(Files.readAllBytes(keyFile));
            return new SecretKeySpec(raw, "AES");
        } catch (Exception e) {
            throw new GeneralSecurityExceptionWrapper(
                    "Failed to load or create session key at " + keyFile, e);
        }
    }

    private static byte[] encrypt(SecretKey key, byte[] iv, byte[] plaintext) {
        try {
            Cipher c = Cipher.getInstance(ALGORITHM);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return c.doFinal(plaintext);
        } catch (Exception e) {
            throw new GeneralSecurityExceptionWrapper("encrypt failed", e);
        }
    }

    private static byte[] decrypt(SecretKey key, Envelope env) {
        try {
            byte[] iv = Base64.getDecoder().decode(env.iv);
            byte[] ciphertext = Base64.getDecoder().decode(env.ciphertext);
            Cipher c = Cipher.getInstance(ALGORITHM);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return c.doFinal(ciphertext);
        } catch (Exception e) {
            throw new GeneralSecurityExceptionWrapper("decrypt failed", e);
        }
    }

    private static void restrictToOwner(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        // Windows ACL: rely on the user profile default (parent dir is already owner-only)
        // and validate at read time via assertOwnerOnly.
    }

    private static void assertOwnerOnly(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            Set<PosixFilePermission> perms = posix.readAttributes().permissions();
            if (perms.contains(PosixFilePermission.GROUP_READ)
                    || perms.contains(PosixFilePermission.OTHERS_READ)) {
                throw new SecurityException(file + " must be owner-only (chmod 600)");
            }
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal owner = Files.getOwner(file);
            for (AclEntry entry : acl.getAcl()) {
                if (entry.principal().equals(owner)) continue;
                if (isPrivilegedSystemPrincipal(entry.principal().getName())) continue;
                if (entry.permissions().contains(AclEntryPermission.READ_DATA)) {
                    throw new SecurityException(
                            file + " grants read to " + entry.principal().getName()
                                    + "; restrict to the owner only.");
                }
            }
        }
    }

    private static boolean isPrivilegedSystemPrincipal(String name) {
        return name.equalsIgnoreCase("NT AUTHORITY\\SYSTEM")
                || name.equalsIgnoreCase("BUILTIN\\Administrators")
                || name.equalsIgnoreCase("NT AUTHORITY\\LOCAL SERVICE")
                || name.equalsIgnoreCase("NT AUTHORITY\\NETWORK SERVICE");
    }

    private record Envelope(Instant savedAt, String iv, String ciphertext) {}

    private static final class GeneralSecurityExceptionWrapper extends RuntimeException {
        GeneralSecurityExceptionWrapper(String message, Throwable cause) { super(message, cause); }
    }
}
