package com.neoproc.financialagent.worker.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.util.function.Consumer;

import static com.neoproc.financialagent.worker.config.PortalFlowsEnvironmentPostProcessor.BANKSTATEMENT_ENABLED;
import static com.neoproc.financialagent.worker.config.PortalFlowsEnvironmentPostProcessor.CAPTURE_ENABLED;
import static com.neoproc.financialagent.worker.config.PortalFlowsEnvironmentPostProcessor.SUBMIT_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the listener-gating contract: a submit-only portal must NOT enable the
 * capture listener (its capture queue is never provisioned), while a portal that
 * declares no {@code flows} keeps both listeners (pre-{@code flows} behaviour).
 * Regression guard for the INS RT-Virtual prod incident where the worker's whole
 * context died trying to resolve a non-existent capture queue.
 */
class PortalFlowsEnvironmentPostProcessorTest {

    private final PortalFlowsEnvironmentPostProcessor processor =
            new PortalFlowsEnvironmentPostProcessor();

    @Test
    void submitOnlyPortalDisablesCaptureKeepsSubmit() {
        withPortalId("ins-rt-virtual", env -> {
            assertEquals(Boolean.FALSE, env.getProperty(CAPTURE_ENABLED, Boolean.class));
            assertEquals(Boolean.TRUE, env.getProperty(SUBMIT_ENABLED, Boolean.class));
        });
    }

    @Test
    void portalWithoutDeclaredFlowsEnablesBoth() {
        withPortalId("mock-payroll", env -> {
            assertEquals(Boolean.TRUE, env.getProperty(CAPTURE_ENABLED, Boolean.class));
            assertEquals(Boolean.TRUE, env.getProperty(SUBMIT_ENABLED, Boolean.class));
        });
    }

    @Test
    void bankStatementPortalEnablesOnlyBankStatement() {
        // A PORTAL_ID=xero worker must register ONLY the bank-statement listener
        // — the payroll capture/submit queues don't exist for it.
        withPortalId("xero", env -> {
            assertEquals(Boolean.FALSE, env.getProperty(CAPTURE_ENABLED, Boolean.class));
            assertEquals(Boolean.FALSE, env.getProperty(SUBMIT_ENABLED, Boolean.class));
            assertEquals(Boolean.TRUE, env.getProperty(BANKSTATEMENT_ENABLED, Boolean.class));
        });
    }

    @Test
    void unknownPortalLeavesFlagsUnsetSoMatchIfMissingApplies() {
        withPortalId("no-such-portal", env -> {
            assertNull(env.getProperty(CAPTURE_ENABLED));
            assertNull(env.getProperty(SUBMIT_ENABLED));
        });
    }

    private void withPortalId(String portalId, Consumer<StandardEnvironment> assertions) {
        String previous = System.getProperty("portal.id");
        System.setProperty("portal.id", portalId);
        try {
            StandardEnvironment env = new StandardEnvironment();
            processor.postProcessEnvironment(env, null);
            assertions.accept(env);
        } finally {
            if (previous == null) {
                System.clearProperty("portal.id");
            } else {
                System.setProperty("portal.id", previous);
            }
        }
    }
}
