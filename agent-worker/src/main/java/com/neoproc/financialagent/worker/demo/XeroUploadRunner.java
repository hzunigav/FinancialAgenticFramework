package com.neoproc.financialagent.worker.demo;

import com.neoproc.financialagent.contract.bankstatement.BankStatementTask;
import com.neoproc.financialagent.contract.bankstatement.BankStatementUploadRequest;
import com.neoproc.financialagent.contract.bankstatement.FileBody;
import com.neoproc.financialagent.contract.bankstatement.TargetSystem;
import com.neoproc.financialagent.contract.payroll.Audit;
import com.neoproc.financialagent.contract.payroll.EnvelopeMeta;
import com.neoproc.financialagent.worker.PortalRunService;
import com.neoproc.financialagent.worker.RunOutcome;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase-2e live-run harness — NOT production. Builds a real
 * {@code bank-statement-upload-request.v1} from a local CSV + system
 * properties and drives {@link PortalRunService#runBankStatement} against the
 * demo org, reusing the seeded session (so no Akamai login). Lets us validate
 * the {@link com.neoproc.financialagent.worker.XeroBankStatementAdapter}
 * end-to-end and tune the {@code VALIDATE-LIVE} selectors, without standing up
 * SQS.
 *
 * <p>Prereqs: seed the session into the local store first —
 * {@code SessionSeeder xero xero-spike/storageState.json} — and run headed
 * ({@code -Dportal.headless=false}).
 *
 * <p>Run (direct java to avoid exec:java's 30s watchdog):
 * <pre>
 * java -Dportal.headless=false `
 *   -Dxero.shortCode=!0X0!! -Dxero.account=090-8007-006543 -Dxero.currency=USD `
 *   -Dxero.csv=docs/BankRecon/sample/xero-statement-sample.csv `
 *   -cp "agent-worker\target\classes;$cp" `
 *   com.neoproc.financialagent.worker.demo.XeroUploadRunner
 * </pre>
 * Optional: {@code -Dxero.orgName -Dxero.iban -Dxero.bankName -Dxero.clientName}
 * {@code -Dxero.openingBalance -Dxero.closingBalance -Dparams.firmId}.
 */
public final class XeroUploadRunner {

    private XeroUploadRunner() {}

    public static void main(String[] args) throws Exception {
        String shortCode = required("xero.shortCode");
        String account = required("xero.account");
        Path csv = Path.of(required("xero.csv"));
        String currency = System.getProperty("xero.currency", "USD");
        String clientName = System.getProperty("xero.clientName", "LIVETEST");
        long firmId = Long.parseLong(System.getProperty("params.firmId", "1"));

        byte[] bytes = Files.readAllBytes(csv);
        List<String> lines = Files.readAllLines(csv).stream().filter(l -> !l.isBlank()).toList();
        String[] header = lines.get(0).split(",", -1);
        int dateCol = indexOf(header, "Date");
        int amountCol = indexOf(header, "Amount");
        List<String> dataRows = lines.subList(1, lines.size());

        int rowCount = dataRows.size();
        BigDecimal net = BigDecimal.ZERO;
        LocalDate min = null, max = null;
        for (String row : dataRows) {
            String[] cols = row.split(",", -1);
            net = net.add(new BigDecimal(cols[amountCol].trim()));
            LocalDate d = parseDate(cols[dateCol].trim());
            if (d != null) {
                min = (min == null || d.isBefore(min)) ? d : min;
                max = (max == null || d.isAfter(max)) ? d : max;
            }
        }
        LocalDate periodStart = prop("xero.periodStart") != null ? LocalDate.parse(prop("xero.periodStart"))
                : (min != null ? min : LocalDate.now());
        LocalDate periodEnd = prop("xero.periodEnd") != null ? LocalDate.parse(prop("xero.periodEnd"))
                : (max != null ? max : LocalDate.now());

        BankStatementTask task = new BankStatementTask(
                BankStatementTask.TYPE, BankStatementTask.OPERATION, TargetSystem.XERO,
                prop("xero.uuid"), prop("xero.orgName"), shortCode,
                clientName, prop("xero.bankName"), account, prop("xero.iban"), currency,
                periodStart, periodEnd, csv.getFileName().toString(),
                rowCount, net.setScale(2).toPlainString(),
                prop("xero.openingBalance"), prop("xero.closingBalance"));

        String sha = sha256Hex(bytes);
        FileBody body = new FileBody(FileBody.FileRef.inline(sha,
                Base64.getEncoder().encodeToString(bytes)));

        String businessKey = clientName + "::" + account + "::" + currency + "::"
                + periodStart.toString().replace("-", "") + "-" + periodEnd.toString().replace("-", "");
        EnvelopeMeta envelope = new EnvelopeMeta(
                UUID.randomUUID().toString(), businessKey, firmId, "es", Instant.now(),
                "agent-worker/xero-live-runner", "live-" + UUID.randomUUID().toString().substring(0, 8));

        BankStatementUploadRequest request = new BankStatementUploadRequest(
                envelope, task, null, body, new Audit(null, null, null, sha));

        System.out.printf("%n=== Xero live upload run ===%n shortCode=%s account=%s currency=%s%n"
                        + " rows=%d netMovement=%s period=%s..%s%n businessKey=%s%n%n",
                shortCode, account, currency, rowCount, net.setScale(2).toPlainString(),
                periodStart, periodEnd, businessKey);

        PortalRunService svc = new PortalRunService(Path.of(System.getProperty("artifacts.dir", "artifacts")));
        Map<String, String> bindings = new HashMap<>();
        bindings.put("params.firmId", String.valueOf(firmId));
        bindings.put("params.businessKey", businessKey);
        bindings.put("params.issuerRunId", envelope.issuerRunId());

        RunOutcome outcome = svc.runBankStatement("xero", request, bindings);

        System.out.printf("%n=== run complete: status=%s runDir=%s ===%n", outcome.status(), outcome.runDir());
        Path resultFile = outcome.runDir().resolve("bank-statement-upload-result.v1.json");
        if (Files.exists(resultFile)) {
            System.out.println(Files.readString(resultFile));
        }
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().replace("*", "").equalsIgnoreCase(name)) return i;
        }
        throw new IllegalArgumentException("CSV header has no '" + name + "' column: " + String.join(",", header));
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw);   // ISO yyyy-MM-dd
        } catch (RuntimeException notIso) {
            try {
                // Xero template uses M/d/yyyy (e.g. 6/1/2026).
                return LocalDate.parse(raw, java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy"));
            } catch (RuntimeException e) {
                return null;   // unknown format; period falls back to props/today
            }
        }
    }

    private static String required(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing -D" + key);
        }
        return v;
    }

    private static String prop(String key) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                .toLowerCase(java.util.Locale.ROOT);
    }
}
