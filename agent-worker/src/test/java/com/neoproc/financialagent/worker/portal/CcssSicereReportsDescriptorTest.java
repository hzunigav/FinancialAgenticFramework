package com.neoproc.financialagent.worker.portal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the CCSS Sicere report descriptor.
 *
 * <p>The login flow is duplicated across three descriptors because the engine
 * has no include mechanism, and each file only carries a comment asking the
 * next author to keep them aligned. A comment cannot fail a build, so the
 * alignment is asserted here instead: if CCSS changes its two-step login and
 * someone updates only {@code ccss-sicere.yaml}, this test says so rather than
 * the report run failing in production with CREDENTIALS_INVALID.
 */
class CcssSicereReportsDescriptorTest {

    @Test
    void descriptorLoadsWithPerClientScope() throws IOException {
        PortalDescriptor descriptor = PortalDescriptorLoader.load("ccss-sicere-reports");

        assertEquals("ccss-sicere-reports", descriptor.id());
        assertTrue(descriptor.hasPerClientCredentials(),
                "reports run resolves credentials per corporate id, like the submit run");
        assertTrue(descriptor.steps().isEmpty(),
                "the adapter drives every report; descriptor steps must stay empty");
    }

    @Test
    void authStepsMatchTheSubmitDescriptor() throws IOException {
        List<PortalDescriptor.Step> submit =
                PortalDescriptorLoader.load("ccss-sicere").authSteps();
        List<PortalDescriptor.Step> reports =
                PortalDescriptorLoader.load("ccss-sicere-reports").authSteps();

        assertEquals(describe(submit), describe(reports),
                "CCSS login flow drifted between ccss-sicere.yaml and "
                + "ccss-sicere-reports.yaml — update both (and the capture descriptor)");
    }

    /** Compares the parts that drive the browser, ignoring incidental fields. */
    private static List<String> describe(List<PortalDescriptor.Step> steps) {
        return steps.stream()
                .map(s -> s.action() + "|" + s.selector() + "|" + s.target() + "|" + s.value())
                .toList();
    }
}
