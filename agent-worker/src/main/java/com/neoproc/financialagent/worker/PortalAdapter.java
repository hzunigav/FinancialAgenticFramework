package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.worker.portal.PortalDescriptor;
import com.microsoft.playwright.Page;

import java.util.List;
import java.util.Map;

/**
 * Per-portal bridge between the generic scrape map and the typed domain
 * record that lands in the run manifest. Also decides whether a Read-Back
 * happens (a portal with a known source-of-truth runs verification; a
 * capture-only portal stashes the scraped record and returns
 * {@code "CAPTURED"}).
 *
 * <p>Adding a portal: implement this interface and register it in
 * {@link Agent#ADAPTERS}. No other engine-level code should need to change.
 */
interface PortalAdapter {

    /**
     * Optional pre-steps hook. Runs after {@code authSteps} succeed and
     * before the main {@code steps} list executes. Write-flow portals
     * use this to navigate to the relevant data page, scrape the current
     * row set, and pre-populate {@code listBindings} so the descriptor's
     * {@code forEach} action has input to loop over.
     *
     * <p>Default: no-op (read-only portals need nothing here).
     */
    default void beforeSteps(PortalDescriptor descriptor,
                             Page page,
                             Map<String, String> bindings,
                             Map<String, List<Map<String, String>>> listBindings,
                             PortalCredentials credentials,
                             RunManifest manifest) {
        // no-op
    }

    /**
     * Map {@code scraped} to a typed record, stash it on the manifest, and
     * (when a source-of-truth is available) fire Read-Back.
     *
     * <p>{@code scrapedRows} is populated only when the descriptor's
     * {@code scrape.rows} block is set (per-row capture); empty otherwise.
     * Adapters that only care about totals can ignore it.
     *
     * @return status for the run: {@code SUCCESS} / {@code MISMATCH} when
     *         verified, {@code CAPTURED} when there is no source-of-truth yet.
     */
    String captureToManifest(Map<String, String> scraped,
                             List<Map<String, String>> scrapedRows,
                             Map<String, String> bindings,
                             PortalCredentials credentials,
                             RunManifest manifest);
}
