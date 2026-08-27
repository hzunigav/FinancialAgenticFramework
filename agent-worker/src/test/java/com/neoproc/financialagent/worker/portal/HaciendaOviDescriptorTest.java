package com.neoproc.financialagent.worker.portal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the hacienda-ovi login recipe: the descriptor must load and its
 * authSteps must carry the confirmed selectors + the email-OTP block, so a
 * typo in the YAML (or a schema drift in the emailOtp mapping) fails CI rather
 * than a live login.
 */
class HaciendaOviDescriptorTest {

    private static PortalDescriptor load() throws Exception {
        return PortalDescriptorLoader.load("hacienda-ovi");
    }

    @Test
    void loadsAsSharedSubmitPortalWithSessionReuse() throws Exception {
        PortalDescriptor d = load();
        assertEquals("hacienda-ovi", d.id());
        assertTrue(d.hasSharedCredentials(), "shared login fronts all firms");
        assertTrue(d.session().enabled(), "session reuse must be on (ttl>0)");
    }

    @Test
    void authSteps_haveConfirmedSelectors_andEmailOtpBlock() throws Exception {
        List<PortalDescriptor.Step> auth = load().authSteps();

        // The cédula + password + submit selectors from the Phase-0 DOM capture.
        assertTrue(hasStep(auth, PortalDescriptor.Action.fill, "#login-txt-ident"));
        assertTrue(hasStep(auth, PortalDescriptor.Action.fill, "#login-txt-password"));
        assertTrue(hasStep(auth, PortalDescriptor.Action.click, "#login-btn-send-form"));

        PortalDescriptor.Step otp = auth.stream()
                .filter(s -> s.action() == PortalDescriptor.Action.emailOtp)
                .findFirst().orElseThrow(() -> new AssertionError("no emailOtp step"));
        assertEquals("#login-txt-pin", otp.selector());
        PortalDescriptor.EmailOtp cfg = otp.emailOtp();
        assertNotNull(cfg, "emailOtp block must deserialize");
        assertEquals("tribucrcorreo@hacienda.go.cr", cfg.fromContains());
        assertTrue(cfg.subjectContains().contains("Validación"));
        // The regex must actually pull the code out of the real body.
        assertTrue(java.util.regex.Pattern.compile(cfg.codeRegex())
                        .matcher("el código de validación es: 14528.").find(),
                "codeRegex must match the Phase-0 sample body");
        assertEquals(120, cfg.timeoutSecondsOrDefault());
    }

    @Test
    void authSteps_endWithLoginFormDisappearanceSignal() throws Exception {
        List<PortalDescriptor.Step> auth = load().authSteps();
        PortalDescriptor.Step last = auth.get(auth.size() - 1);
        assertEquals(PortalDescriptor.Action.waitForSelector, last.action());
        assertEquals("#login-txt-ident", last.selector());
        assertEquals("hidden", last.state(), "logged-in signal = login field goes hidden");
    }

    private static boolean hasStep(List<PortalDescriptor.Step> steps,
                                   PortalDescriptor.Action action, String selector) {
        return steps.stream().anyMatch(s -> s.action() == action && selector.equals(s.selector()));
    }
}
