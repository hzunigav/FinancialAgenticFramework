package com.financialagent.worker.portal;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the shadow-mode guard. The guard check runs before the engine
 * touches the Playwright page, so a null Page is fine for these cases —
 * we're testing descriptor-driven control flow, not browser automation.
 */
class PortalEngineShadowModeTest {

    @Test
    void submitStep_inShadowMode_throwsShadowHaltBeforeTouchingPage() {
        PortalDescriptor.Step submit = new PortalDescriptor.Step(
                PortalDescriptor.Action.click,
                "button[type='submit']",
                null, null, null, null, null, Boolean.TRUE);

        Map<String, Object> audits = new HashMap<>();
        PortalEngine engine = new PortalEngine(
                null, new HashMap<>(),
                (action, target) -> audits.put(action, target),
                prompt -> { throw new AssertionError("operator should not be prompted"); },
                true);

        PortalEngine.ShadowHalt halt = assertThrows(
                PortalEngine.ShadowHalt.class,
                () -> engine.runSteps("http://example", List.of(submit)));

        assertTrue(halt.getMessage().contains("button[type='submit']"),
                "ShadowHalt message should identify the blocked selector");
        assertEquals("button[type='submit']", audits.get("BLOCKED-SUBMIT"),
                "audit listener should record the blocked submit");
    }

    @Test
    void nonSubmitStep_inShadowMode_isNotBlockedByGuard() {
        // A plain fill step (submit flag absent) should pass the guard. We
        // can't actually execute the fill without a Page, so we assert that
        // the ShadowHalt is not the exception raised — anything else would
        // be a downstream Playwright NPE, which is fine for this test.
        PortalDescriptor.Step fill = new PortalDescriptor.Step(
                PortalDescriptor.Action.fill,
                "input[name='q']",
                null, "value", null, null, null, null);

        PortalEngine engine = new PortalEngine(
                null, new HashMap<>(),
                (a, t) -> {},
                prompt -> "",
                true);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> engine.runSteps("http://example", List.of(fill)));
        assertTrue(!(thrown instanceof PortalEngine.ShadowHalt),
                "non-submit step should not trip the shadow guard");
    }
}
