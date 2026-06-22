package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.session.SessionStore;
import com.neoproc.financialagent.common.session.SessionStores;
import com.neoproc.financialagent.worker.portal.PortalDescriptor;
import com.neoproc.financialagent.worker.portal.PortalEngine;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

import java.time.Duration;
import java.util.Optional;

/**
 * Isolated authentication phase shared by all portal adapters.
 *
 * <p>Every portal run — whether a full capture, a submit, or a login probe —
 * passes through {@link #login} before any adapter-specific steps execute.
 * This keeps session management, auth-step execution, and session-save logic
 * in one place so new adapters and the probe path get it for free.
 */
final class PortalAuthService {

    private PortalAuthService() {}

    /**
     * Runs the authentication phase for the given portal.
     *
     * <p>If a valid saved session exists it is already loaded into the
     * browser context (via {@link BrowserContext} storageState); this method
     * navigates to the base URL and waits for network idle instead of
     * replaying the full login flow.
     *
     * @return {@code true} if a saved session was reused, {@code false} if
     *         the full {@code authSteps} sequence was executed
     */
    static boolean login(PortalEngine engine,
                         PortalDescriptor descriptor,
                         BrowserContext context,
                         Page page,
                         RunManifest manifest) {
        SessionStore sessionStore = SessionStores.defaultStore();
        Optional<String> savedSession = loadSavedSession(sessionStore, descriptor);

        if (savedSession.isEmpty()) {
            engine.runSteps(descriptor.baseUrl(), descriptor.authSteps());
            saveSessionIfEnabled(sessionStore, descriptor, context);
            manifest.step("auth", "authSteps completed for portal=" + descriptor.id());
            return false;
        } else {
            page.navigate(descriptor.baseUrl());
            // DOMCONTENTLOADED, not NETWORKIDLE: SPA portals (e.g. Xero) keep
            // background traffic alive and never reach network-idle, which would
            // hang this reuse path. Adapters wait on concrete selectors anyway.
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            manifest.step("auth-skipped",
                    "session-reused; navigated to " + descriptor.baseUrl() + " (domcontentloaded)");
            return true;
        }
    }

    static Optional<String> loadSavedSession(SessionStore store, PortalDescriptor descriptor) {
        PortalDescriptor.SessionConfig session = descriptor.session();
        if (session == null || !session.enabled()) {
            return Optional.empty();
        }
        return store.load(descriptor.id(), Duration.ofMinutes(session.ttlMinutes()));
    }

    static void saveSessionIfEnabled(SessionStore store,
                                     PortalDescriptor descriptor,
                                     BrowserContext context) {
        PortalDescriptor.SessionConfig session = descriptor.session();
        if (session == null || !session.enabled()) {
            return;
        }
        store.save(descriptor.id(), context.storageState());
    }
}
