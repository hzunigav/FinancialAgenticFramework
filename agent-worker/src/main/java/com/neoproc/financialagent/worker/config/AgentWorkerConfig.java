package com.neoproc.financialagent.worker.config;

import com.neoproc.financialagent.worker.PortalRunService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Wires the plain-Java {@link PortalRunService} into the Spring context.
 * Declared as a {@code @Bean} (not {@code @Component}) so it can be
 * {@code @MockBean}-replaced cleanly in integration tests without Spring
 * scanning ever touching the Playwright code paths.
 */
@Configuration
public class AgentWorkerConfig {

    @Value("${agent.worker.artifacts-dir:artifacts}")
    private String artifactsDir;

    @Bean
    PortalRunService portalRunService() {
        Path root = Paths.get(artifactsDir).toAbsolutePath().normalize();
        return new PortalRunService(root);
    }
}
