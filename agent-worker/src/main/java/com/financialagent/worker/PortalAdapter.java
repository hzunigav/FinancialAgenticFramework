package com.financialagent.worker;

import com.financialagent.common.credentials.PortalCredentials;

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
     * Map {@code scraped} to a typed record, stash it on the manifest, and
     * (when a source-of-truth is available) fire Read-Back.
     *
     * @return status for the run: {@code SUCCESS} / {@code MISMATCH} when
     *         verified, {@code CAPTURED} when there is no source-of-truth yet.
     */
    String captureToManifest(Map<String, String> scraped,
                             Map<String, String> bindings,
                             PortalCredentials credentials,
                             RunManifest manifest);
}
