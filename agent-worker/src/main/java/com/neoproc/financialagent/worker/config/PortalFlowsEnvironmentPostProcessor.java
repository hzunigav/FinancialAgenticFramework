package com.neoproc.financialagent.worker.config;

import com.neoproc.financialagent.worker.portal.PortalDescriptor;
import com.neoproc.financialagent.worker.portal.PortalDescriptorLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derives {@code agent.worker.capture-enabled} / {@code agent.worker.submit-enabled}
 * from the active portal descriptor's {@code flows} and contributes them to the
 * environment before bean creation, so the {@code @ConditionalOnProperty} guards
 * on the capture/submit listeners can fire.
 *
 * <p>Why this exists: the worker image is portal-agnostic and statically declares
 * both a capture and a submit {@code @SqsListener}. A submit-only portal (e.g.
 * INS RT-Virtual — capture is owned by AutoPlanilla) has no capture queue
 * provisioned. Under {@link io.awspring.cloud.sqs.listener.QueueNotFoundStrategy#FAIL}
 * the capture listener's missing-queue resolution aborts the whole Spring context,
 * silently killing the submit listener too. Gating registration on the descriptor's
 * declared flows keeps submit-only (and capture-only) portals from registering a
 * listener for a queue that was never created.
 *
 * <p>Runs as an {@code EnvironmentPostProcessor} (registered in
 * {@code META-INF/spring.factories}) so the properties are present before
 * {@code @ConditionalOnProperty} is evaluated. If the descriptor can't be resolved
 * the processor sets nothing — the listeners' {@code matchIfMissing = true} then
 * preserves the historical both-listeners behaviour.
 */
public class PortalFlowsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String CAPTURE_ENABLED = "agent.worker.capture-enabled";
    static final String SUBMIT_ENABLED = "agent.worker.submit-enabled";
    static final String BANKSTATEMENT_ENABLED = "agent.worker.bankstatement-enabled";
    private static final String PROPERTY_SOURCE_NAME = "portalFlows";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String portalId = resolvePortalId(environment);
        if (portalId == null || !PortalDescriptorLoader.exists(portalId)) {
            // No descriptor → leave the flags unset; matchIfMissing=true keeps
            // both listeners registered (pre-flows behaviour).
            return;
        }

        PortalDescriptor descriptor;
        try {
            descriptor = PortalDescriptorLoader.load(portalId);
        } catch (Exception e) {
            // Descriptor exists but failed to parse — don't gate listeners on a
            // broken read; let normal startup surface the parse error instead.
            return;
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put(CAPTURE_ENABLED, descriptor.handlesCapture());
        props.put(SUBMIT_ENABLED, descriptor.handlesSubmit());
        props.put(BANKSTATEMENT_ENABLED, descriptor.handlesBankStatement());
        // First in the list = highest precedence; deliberately authoritative over
        // any application.yml default so the descriptor is the single source of truth.
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
    }

    /**
     * Resolve the portal id the same way the running app will, tolerating early
     * post-processor ordering where application.yml may not be bound yet.
     * Mirrors the {@code portal.id: ${PORTAL_ID:mock-payroll}} default.
     */
    private static String resolvePortalId(ConfigurableEnvironment environment) {
        String fromProp = System.getProperty("portal.id");
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp;
        }
        String fromEnvVar = System.getenv("PORTAL_ID");
        if (fromEnvVar != null && !fromEnvVar.isBlank()) {
            return fromEnvVar;
        }
        String fromEnvironment = environment.getProperty("portal.id");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        return "mock-payroll";
    }
}
