package com.neoproc.financialagent.worker.demo;

import com.neoproc.financialagent.common.crypto.EnvelopeCipher;
import com.neoproc.financialagent.contract.payroll.CaptureResultBody;
import com.neoproc.financialagent.contract.payroll.PayrollCaptureResult;
import com.neoproc.financialagent.worker.envelope.EnvelopeIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Demo helper: reads a {@code payroll-capture-result.v1} envelope from
 * disk, decrypts the result block (if encrypted), and writes the
 * {@code employees[]} array out as cleartext JSON suitable for POSTing
 * to the harness's {@code /employees/seed} endpoint.
 *
 * <p>Used by {@code demo/run-pipeline.sh} to bridge the encrypted
 * capture artifact into the seed endpoint. Not part of the production
 * flow — Praxis would decrypt via its own cipher instance.
 *
 * <p>Args: {@code <envelope-path> <output-json-path>}
 */
public final class CaptureEnvelopeInspector {

    private CaptureEnvelopeInspector() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: CaptureEnvelopeInspector <envelope-path> <output-json-path>");
            System.exit(2);
        }
        Path envelopeFile = Path.of(args[0]);
        Path outFile = Path.of(args[1]);

        PayrollCaptureResult envelope = EnvelopeIo.read(envelopeFile, PayrollCaptureResult.class);
        CaptureResultBody body = decodeBody(envelope);

        // Write just the employees array — the seed endpoint accepts
        // employees with grossSalary/displayName fields directly.
        byte[] json = EnvelopeIo.MAPPER.writeValueAsBytes(body.employees());
        Files.write(outFile, json);

        System.out.println("Wrote " + body.employees().size() + " employees to " + outFile);
    }

    private static CaptureResultBody decodeBody(PayrollCaptureResult envelope) {
        if (envelope.encryption() == null) {
            return EnvelopeIo.MAPPER.convertValue(envelope.result(), CaptureResultBody.class);
        }
        if (!(envelope.result() instanceof String ciphertext)) {
            throw new IllegalStateException(
                    "Envelope is marked encrypted but result field is not a string ciphertext");
        }
        EnvelopeCipher cipher = EnvelopeIo.defaultCipher();
        return EnvelopeIo.decryptBody(ciphertext, envelope.encryption(), cipher, CaptureResultBody.class);
    }
}
