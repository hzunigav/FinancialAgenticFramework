package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.common.domain.ReportSnapshot;
import com.neoproc.financialagent.common.verify.ReadBackVerifier;
import com.neoproc.financialagent.common.verify.VerificationResult;
import com.neoproc.financialagent.worker.portal.SnapshotMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

final class MockPortalAdapter implements PortalAdapter {

    @Override
    public String captureToManifest(Map<String, String> scraped,
                                    List<Map<String, String>> scrapedRows,
                                    Map<String, String> bindings,
                                    PortalCredentials credentials,
                                    RunManifest manifest) {
        ReportSnapshot scrapedSnapshot = SnapshotMapper.toSnapshot(scraped);
        ReportSnapshot source = new ReportSnapshot(credentials.require("username"), LocalDate.now());
        VerificationResult<ReportSnapshot> result = ReadBackVerifier.verify(source, scrapedSnapshot);

        manifest.scraped = scrapedSnapshot;
        manifest.verification = toManifestVerification(result);
        manifest.step("verify", result.status().name());
        return result.matched() ? "SUCCESS" : "MISMATCH";
    }

    private static RunManifest.Verification toManifestVerification(
            VerificationResult<ReportSnapshot> result) {
        RunManifest.Verification v = new RunManifest.Verification();
        v.status = result.status().name();
        v.source = result.source();
        v.scraped = result.scraped();
        v.diffs = result.diffs().stream()
                .map(d -> new RunManifest.Verification.Diff(d.field(), d.source(), d.scraped()))
                .toList();
        return v;
    }
}
