package com.neoproc.financialagent.worker.demo;

import com.neoproc.financialagent.worker.auth.TotpGenerator;

import java.time.Instant;

/**
 * Tiny pre-deploy safety check — prints the current 6-digit TOTP for a base32
 * seed so you can confirm it matches your authenticator app (and therefore what
 * Xero will accept) BEFORE baking the seed into the prod secret. If the code
 * here matches your phone at the same moment, the worker's autonomous 2FA login
 * will pass.
 *
 * <p>Run (PowerShell):
 * <pre>
 * $cp = Get-Content agent-worker\target\cp.txt
 * java '-Dxero.totpSeed=YOURBASE32SEED' -cp "agent-worker\target\classes;$cp" `
 *   com.neoproc.financialagent.worker.demo.TotpCheck
 * </pre>
 * Or pass the seed as the first argument. Prints the current code, the seconds
 * left in the 30s window, and the adjacent codes (so a near-boundary compare is
 * unambiguous). The seed is never logged in full.
 */
public final class TotpCheck {

    private TotpCheck() {}

    public static void main(String[] args) {
        String seed = System.getProperty("xero.totpSeed",
                args.length > 0 ? args[0] : System.getenv("XERO_TOTP_SEED"));
        if (seed == null || seed.isBlank()) {
            System.err.println("Provide the seed: -Dxero.totpSeed=... (or as arg1, or XERO_TOTP_SEED env)");
            System.exit(2);
        }

        long now = Instant.now().getEpochSecond();
        int into = (int) (now % 30);
        int left = 30 - into;

        String prev = TotpGenerator.generate(seed, now - 30, 6, 30, "HmacSHA1");
        String curr = TotpGenerator.generate(seed, now,      6, 30, "HmacSHA1");
        String next = TotpGenerator.generate(seed, now + 30, 6, 30, "HmacSHA1");

        System.out.println();
        System.out.println("=== TOTP check (seed " + maskSeed(seed) + ") ===");
        System.out.println("  CURRENT code : " + curr + "   (" + left + "s left in this window)");
        System.out.println("  prev / next  : " + prev + " / " + next);
        System.out.println();
        System.out.println("  Compare CURRENT to your authenticator app now. If they match, the");
        System.out.println("  seed is correct and the worker's 2FA login will pass. (Near a window");
        System.out.println("  boundary your app may show 'next' - that's fine.)");
    }

    private static String maskSeed(String seed) {
        String s = seed.replace(" ", "").replace("-", "");
        if (s.length() <= 4) {
            return "****";
        }
        return s.substring(0, 2) + "..." + s.substring(s.length() - 2) + " (" + s.length() + " chars)";
    }
}
