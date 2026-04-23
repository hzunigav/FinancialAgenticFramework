package com.neoproc.financialagent.common.session;

import java.time.Duration;
import java.util.Optional;

/**
 * Storage for a portal's authenticated browser session (Playwright
 * {@code storageState} JSON). Same interface for dev (encrypted local
 * file) and prod (AWS Secrets Manager) so swaps happen at wiring time.
 */
public interface SessionStore {

    /**
     * Returns the saved session JSON for {@code portalId} iff it exists
     * and its age is within {@code maxAge}. Stale sessions are purged.
     */
    Optional<String> load(String portalId, Duration maxAge);

    /**
     * Persists {@code sessionJson} for {@code portalId}. Overwrites any
     * existing entry.
     */
    void save(String portalId, String sessionJson);

    /** Removes any saved session for {@code portalId}. No-op if absent. */
    void purge(String portalId);
}
