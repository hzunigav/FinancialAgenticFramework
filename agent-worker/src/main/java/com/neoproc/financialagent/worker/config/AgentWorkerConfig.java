package com.neoproc.financialagent.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neoproc.financialagent.worker.PortalRunService;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.awspring.cloud.sqs.support.converter.SqsMessagingMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

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

    /**
     * Override the default SqsTemplate to use FAIL strategy instead of CREATE.
     * SCA 3.2.1's default CREATE strategy calls sqs:CreateQueue on every send
     * to resolve the queue URL (idempotent get-or-create). FAIL strategy uses
     * sqs:GetQueueUrl instead, which is the correct behaviour for pre-provisioned
     * queues and requires no CreateQueue permission.
     */
    @Bean
    SqsTemplate sqsTemplate(SqsAsyncClient sqsAsyncClient, ObjectMapper objectMapper) {
        MappingJackson2MessageConverter payloadConverter = new MappingJackson2MessageConverter();
        payloadConverter.setObjectMapper(objectMapper);
        payloadConverter.setSerializedPayloadClass(String.class);

        SqsMessagingMessageConverter converter = new SqsMessagingMessageConverter();
        converter.setPayloadMessageConverter(payloadConverter);

        return SqsTemplate.builder()
                .sqsAsyncClient(sqsAsyncClient)
                .configure(options -> options
                        .queueNotFoundStrategy(QueueNotFoundStrategy.FAIL))
                .messageConverter(converter)
                .build();
    }
}
