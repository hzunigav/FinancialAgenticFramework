package com.neoproc.financialagent.worker;

import com.neoproc.financialagent.common.credentials.PortalCredentials;
import com.neoproc.financialagent.common.crypto.EnvelopeCipher;
import com.neoproc.financialagent.common.match.EmployeeMatcher;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.CaptureResultBody;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.contract.payroll.EnvelopeStatus;
import com.neoproc.financialagent.contract.payroll.PayrollCaptureResult;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitRequest;
import com.neoproc.financialagent.contract.payroll.PayrollSubmitResult;
import com.neoproc.financialagent.contract.payroll.RosterDiff;
import com.neoproc.financialagent.contract.payroll.SubmitRequestBody;
import com.neoproc.financialagent.contract.payroll.SubmitResultBody;
import com.neoproc.financialagent.contract.payroll.SubmitTask;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;
import com.neoproc.financialagent.worker.portal.PortalDescriptor;
import com.neoproc.financialagent.worker.portal.PortalScraper;
import com.microsoft.playwright.Page;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Write-flow adapter for the mock payroll harness. Reads either a
 * {@link PayrollSubmitRequest} envelope (BPM-shape) or — for local
 * pipelining without a BPM in between — a {@link PayrollCaptureResult}
 * envelope from the upstream AutoPlanilla run, and treats it as the
 * canonical input.
 *
 * <p>Emits a {@link PayrollSubmitResult} envelope with totals + per-row
 * proof + {@link RosterDiff}. Status routes per
 * PayrollOrchestrationFlow.md §4: PARTIAL when roster gaps exist,
 * MISMATCH when totals diverge, SUCCESS when both clean.
 *
 * <p>Legacy {@code -Dparams.salary.<id>=<amount>} input is still
 * accepted to keep existing manual smoke tests working.
 */
final class MockPayrollAdapter implements PortalAdapter {

    private static final String PARAM_SALARY_PREFIX = "params.salary.";
    private static final String PARAM_NAME_PREFIX = "params.canonicalName.";
    private static final String UPDATES_BINDING = "params.employeeUpdates";

    // Captured during beforeSteps, consumed in captureToManifest.
    // canonicalGrandTotal is the authoritative reconciliation target: the
    // sum of EVERY canonical employee's salary, including ones that didn't
    // match a portal row (missingFromPortal). It must equal the portal's
    // post-submit grand total for the run to be SUCCESS; any drift shifts
    // the gap by exactly one (or more) employees' salary, which is why
    // rosterDiff is the human-readable explanation of a mismatch.
    private BigDecimal canonicalGrandTotal = BigDecimal.ZERO;
    private BigDecimal canonicalSubmittedTotal = BigDecimal.ZERO;
    private int canonicalUpdatedCount = 0;
    private RosterDiff rosterDiff = new RosterDiff(List.of(), List.of());
    private List<SubmitResultBody.SubmittedRow> submittedRows = List.of();
    private String sourceCaptureEnvelopeId; // for traceability

    @Override
    public void beforeSteps(PortalDescriptor descriptor,
                            Page page,
                            Map<String, String> bindings,
                            Map<String, List<Map<String, String>>> listBindings,
                            PortalCredentials credentials,
                            RunManifest manifest) {
        page.navigate(descriptor.baseUrl() + "/employees");
        page.waitForSelector("tr.employee-row");

        // Scrape the displayed roster — id, name, and the current salary
        // value (read from the input via the value: prefix).
        List<Map<String, String>> displayed = new PortalScraper(page).scrapeRows(
                "tr.employee-row",
                Map.of(
                        "id", ".id-cell",
                        "name", ".name-cell",
                        "currentSalary", "value:input.salary-input"));
        List<String> displayedIds = displayed.stream().map(r -> r.get("id")).toList();
        List<String> displayedNames = displayed.stream().map(r -> r.get("name")).toList();

        // Source-of-truth: where does the canonical roster come from?
        List<Canonical> canonicals = loadCanonical(bindings);
        manifest.step("source",
                "canonicals=" + canonicals.size()
                        + (sourceCaptureEnvelopeId != null
                                ? " sourceCaptureEnvelopeId=" + sourceCaptureEnvelopeId
                                : " (legacy params.salary.* input)"));

        if (canonicals.isEmpty()) {
            throw new IllegalStateException(
                    "mock-payroll requires either -Dsource.captureEnvelope=<path>, "
                            + "-Dsource.submitRequest=<path>, or at least one "
                            + "-Dparams.salary.<id>=<amount>");
        }

        // Match each canonical against the displayed roster.
        List<Map<String, String>> updates = new ArrayList<>();
        List<SubmitResultBody.SubmittedRow> matchedRows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;
        boolean[] displayedMatched = new boolean[displayed.size()];
        List<RosterDiff.MissingFromPortal> missingFromPortal = new ArrayList<>();

        for (Canonical c : canonicals) {
            grandTotal = grandTotal.add(c.salary());
            Optional<Integer> matched = c.name() != null
                    ? EmployeeMatcher.match(c.id(), c.name(), displayedIds, displayedNames)
                    : EmployeeMatcher.matchById(c.id(), displayedIds);
            if (matched.isEmpty()) {
                missingFromPortal.add(new RosterDiff.MissingFromPortal(
                        c.id(), c.name(), c.salary(), c.attributes()));
                manifest.step("match-skip",
                        "canonicalId=" + c.id() + " (no displayed row or name mismatch)");
                continue;
            }
            int idx = matched.get();
            displayedMatched[idx] = true;

            Map<String, String> row = new LinkedHashMap<>();
            row.put("index", Integer.toString(idx));
            row.put("id", displayedIds.get(idx));
            row.put("salary", c.salary().toPlainString());
            updates.add(row);

            matchedRows.add(new SubmitResultBody.SubmittedRow(
                    displayedIds.get(idx), displayedNames.get(idx), c.salary()));
            total = total.add(c.salary());
            manifest.step("match-hit",
                    "canonicalId=" + c.id() + " -> index=" + idx
                            + " displayedId=" + displayedIds.get(idx));
        }

        // Anything displayed but never matched → likely termination candidate.
        List<RosterDiff.MissingFromPayroll> missingFromPayroll = new ArrayList<>();
        for (int i = 0; i < displayed.size(); i++) {
            if (!displayedMatched[i]) {
                BigDecimal lastKnown = parseMoneyOrNull(displayed.get(i).get("currentSalary"));
                missingFromPayroll.add(new RosterDiff.MissingFromPayroll(
                        displayedIds.get(i), displayedNames.get(i), lastKnown));
            }
        }

        listBindings.put(UPDATES_BINDING, updates);
        canonicalGrandTotal = grandTotal.setScale(2, RoundingMode.HALF_UP);
        canonicalSubmittedTotal = total.setScale(2, RoundingMode.HALF_UP);
        canonicalUpdatedCount = updates.size();
        submittedRows = List.copyOf(matchedRows);
        rosterDiff = new RosterDiff(missingFromPortal, missingFromPayroll);
        manifest.step("roster-diff",
                "missingFromPortal=" + missingFromPortal.size()
                        + " missingFromPayroll=" + missingFromPayroll.size()
                        + " canonicalGrandTotal=" + canonicalGrandTotal
                        + " canonicalSubmittedTotal=" + canonicalSubmittedTotal);
    }

    @Override
    public String captureToManifest(Map<String, String> scraped,
                                    List<Map<String, String>> scrapedRows,
                                    Map<String, String> bindings,
                                    PortalCredentials credentials,
                                    RunManifest manifest) {
        BigDecimal serverGrandTotal = parseMoney(require(scraped, "grandTotal"));
        BigDecimal serverSubmittedTotal = parseMoney(require(scraped, "submittedTotal"));
        int serverUpdatedCount = parseInt(require(scraped, "updatedCount"));

        // Grand-total reconciliation: the portal's post-submit total must
        // equal the sum of every salary we pulled from the source-of-truth.
        // Any drift (missingFromPortal / missingFromPayroll) shifts the two
        // by exactly the drifted employees' salaries, so totals and
        // rosterDiff agree on the same root cause.
        boolean totalsMatch = canonicalGrandTotal.compareTo(serverGrandTotal) == 0;

        String status;
        if (!totalsMatch) {
            status = EnvelopeStatus.MISMATCH;
        } else if (!rosterDiff.isEmpty()) {
            status = EnvelopeStatus.PARTIAL;
        } else {
            status = EnvelopeStatus.SUCCESS;
        }

        manifest.step("verify",
                "status=" + status
                        + " canonicalGrandTotal=" + canonicalGrandTotal
                        + " serverGrandTotal=" + serverGrandTotal
                        + " delta(canonical)=" + canonicalSubmittedTotal
                        + "/" + canonicalUpdatedCount
                        + " delta(server)=" + serverSubmittedTotal
                        + "/" + serverUpdatedCount);

        if (EnvelopeStatus.MISMATCH.equals(status) || EnvelopeStatus.PARTIAL.equals(status)) {
            System.out.println();
            System.out.println("*** HITL REVIEW REQUIRED — status=" + status + " ***");
            if (EnvelopeStatus.MISMATCH.equals(status)) {
                BigDecimal gap = serverGrandTotal.subtract(canonicalGrandTotal);
                System.out.println("Grand totals diverged: canonical=" + canonicalGrandTotal
                        + " portal=" + serverGrandTotal
                        + " (portal - canonical = " + gap + ")");
            }
            if (!rosterDiff.isEmpty()) {
                System.out.println("Roster diff:");
                rosterDiff.missingFromPortal().forEach(m -> System.out.println(
                        "  missingFromPortal id=" + m.id() + " name=" + m.name()
                                + " expectedSalary=" + m.expectedSalary()));
                rosterDiff.missingFromPayroll().forEach(m -> System.out.println(
                        "  missingFromPayroll id=" + m.id() + " displayName=" + m.displayName()
                                + " lastKnownSalary=" + m.lastKnownSalary()));
            }
            System.out.println("****************************");
        }

        writeEnvelope(status, serverGrandTotal, serverUpdatedCount, bindings, manifest);
        return status;
    }

    private void writeEnvelope(String status,
                               BigDecimal serverTotal,
                               int serverCount,
                               Map<String, String> bindings,
                               RunManifest manifest) {
        long firmId = Long.parseLong(require(bindings, "params.firmId"));
        Path runDir = Path.of(require(bindings, "runtime.runDir"));
        String runId = require(bindings, "runtime.runId");

        EnvelopeCipher cipher = EnvelopeIo.defaultCipher();

        SubmitResultBody body = new SubmitResultBody(
                status,
                null, // mock harness does not issue a portal confirmation id today
                new SubmitResultBody.Totals("CRC", serverTotal, serverCount),
                submittedRows,
                rosterDiff,
                null);

        EnvelopeIo.EncryptedPayload encrypted = EnvelopeIo.encryptBody(body, cipher, firmId);

        EnvelopeMeta envelope = new EnvelopeMeta(
                UUID.randomUUID().toString(),
                bindings.getOrDefault("params.businessKey", "mock-payroll::" + runId),
                firmId,
                "es",
                Instant.now(),
                "agent-worker/mock-payroll",
                runId);

        SubmitTask task = SubmitTask.forSalaries(
                "mock-payroll",
                sourceCaptureEnvelopeId,
                null, null);

        Audit audit = new Audit("manifest.json", null, null, encrypted.payloadSha256());

        PayrollSubmitResult envelopeRecord = new PayrollSubmitResult(
                PayrollSubmitResult.SCHEMA,
                envelope,
                task,
                encrypted.meta(),
                encrypted.ciphertext(),
                audit);

        Path envelopeFile = runDir.resolve("payroll-submit-result.v1.json");
        EnvelopeIo.write(envelopeRecord, envelopeFile);
        manifest.step("envelope", envelopeFile.getFileName()
                + " (envelopeId=" + envelope.envelopeId() + ", encrypted)");
    }

    /**
     * Resolve the canonical employee list from one of three input shapes,
     * in priority order:
     * <ol>
     *   <li>{@code -Dsource.submitRequest=<path>} — proper BPM-shape envelope.</li>
     *   <li>{@code -Dsource.captureEnvelope=<path>} — upstream capture envelope.
     *       Convenient for local pipelining (AutoPlanilla → mock-payroll)
     *       without going through Praxis.</li>
     *   <li>Legacy {@code -Dparams.salary.<id>=<amount>} system properties.</li>
     * </ol>
     */
    private List<Canonical> loadCanonical(Map<String, String> bindings) {
        String submitPath = bindings.get("params.source.submitRequest");
        if (submitPath != null && !submitPath.isBlank()) {
            return readSubmitRequest(Path.of(submitPath), bindings);
        }
        String capturePath = bindings.get("params.source.captureEnvelope");
        if (capturePath != null && !capturePath.isBlank()) {
            return readCaptureEnvelope(Path.of(capturePath), bindings);
        }
        return readLegacyParams(bindings);
    }

    private List<Canonical> readSubmitRequest(Path path, Map<String, String> bindings) {
        PayrollSubmitRequest env = EnvelopeIo.read(path, PayrollSubmitRequest.class);
        sourceCaptureEnvelopeId = env.task() != null ? env.task().sourceCaptureEnvelopeId() : null;
        SubmitRequestBody body = decodeBody(env.encryption(), env.request(),
                SubmitRequestBody.class, bindings);
        return body.employees().stream()
                .map(e -> new Canonical(e.id(), e.displayName(), e.salary(), e.attributes()))
                .toList();
    }

    private List<Canonical> readCaptureEnvelope(Path path, Map<String, String> bindings) {
        PayrollCaptureResult env = EnvelopeIo.read(path, PayrollCaptureResult.class);
        sourceCaptureEnvelopeId = env.envelope() != null ? env.envelope().envelopeId() : null;
        CaptureResultBody body = decodeBody(env.encryption(), env.result(),
                CaptureResultBody.class, bindings);
        return body.employees().stream()
                .map(e -> new Canonical(e.id(), e.displayName(), e.grossSalary(), e.attributes()))
                .toList();
    }

    private static <T> T decodeBody(com.neoproc.financialagent.contract.payroll.Encryption meta,
                                    Object payloadField,
                                    Class<T> bodyType,
                                    Map<String, String> bindings) {
        if (meta == null) {
            // Cleartext envelope — Jackson deserialized result/request as a Map
            return EnvelopeIo.MAPPER.convertValue(payloadField, bodyType);
        }
        if (!(payloadField instanceof String ciphertext)) {
            throw new IllegalStateException(
                    "Envelope marked encrypted but payload field is not a ciphertext string");
        }
        EnvelopeCipher cipher = EnvelopeIo.defaultCipher();
        return EnvelopeIo.decryptBody(ciphertext, meta, cipher, bodyType);
    }

    private static List<Canonical> readLegacyParams(Map<String, String> bindings) {
        Map<String, String> salaries = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : bindings.entrySet()) {
            if (e.getKey().startsWith(PARAM_SALARY_PREFIX)) {
                salaries.put(e.getKey().substring(PARAM_SALARY_PREFIX.length()), e.getValue());
            } else if (e.getKey().startsWith(PARAM_NAME_PREFIX)) {
                names.put(e.getKey().substring(PARAM_NAME_PREFIX.length()), e.getValue());
            }
        }
        List<Canonical> out = new ArrayList<>(salaries.size());
        salaries.forEach((id, amount) ->
                out.add(new Canonical(id, names.get(id), new BigDecimal(amount), Map.of())));
        return out;
    }

    private static BigDecimal parseMoney(String raw) {
        return new BigDecimal(raw.replace(",", "").trim()).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal parseMoneyOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return parseMoney(raw);
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    private static int parseInt(String raw) {
        return Integer.parseInt(raw.trim());
    }

    private static String require(Map<String, String> map, String key) {
        String v = map.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing binding: " + key);
        }
        return v;
    }

    /** Internal canonical employee shape, source-agnostic. */
    private record Canonical(String id, String name, BigDecimal salary, Map<String, String> attributes) {
        Canonical {
            attributes = attributes == null ? Map.of() : attributes;
        }
    }
}
