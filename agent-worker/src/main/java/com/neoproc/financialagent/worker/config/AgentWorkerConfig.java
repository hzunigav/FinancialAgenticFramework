package com.neoproc.financialagent.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neoproc.financialagent.worker.PortalRunService;
import com.neoproc.financialagent.worker.artifact.S3ArtifactStore;
import com.neoproc.financialagent.worker.ecs.EcsTaskProtectionClient;
import com.neoproc.financialagent.worker.ecs.TaskProtection;
import com.neoproc.financialagent.worker.ecs.TaskProtectionInterceptor;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.awspring.cloud.sqs.support.converter.SqsMessagingMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
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

    // Bucket name is intentionally absent from application.yml so local
    // dev runs without S3 configured. Production deploys must inject
    // ARTIFACTS_BUCKET; when blank, S3ArtifactStore.isEnabled() returns
    // false and upload is skipped silently.
    @Value("${agent.worker.artifacts-bucket:}")
    private String artifactsBucket;

    // Mirrors the SQS queue prefix — same env var, same value — so the
    // S3 key prefix and the queue prefix stay in sync without a second
    // FINANCEAGENT_SECRETS_ENV_PREFIX wiring on the task definition.
    @Value("${agent.worker.queue-prefix:dev}")
    private String envPrefix;

    @Value("${spring.cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    @Bean
    PortalRunService portalRunService(S3ArtifactStore s3ArtifactStore) {
        Path root = Paths.get(artifactsDir).toAbsolutePath().normalize();
        return new PortalRunService(root, s3ArtifactStore);
    }

    @Bean
    S3AsyncClient s3AsyncClient() {
        return S3AsyncClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    @Bean
    S3ArtifactStore s3ArtifactStore(S3AsyncClient s3AsyncClient) {
        return new S3ArtifactStore(s3AsyncClient, artifactsBucket, envPrefix);
    }

    // Declaring our own factory replaces SCA's auto-configured one, and that one
    // is the only place spring.cloud.aws.sqs.listener.* is applied. Without
    // reading them here the application.yml pin is silently ignored and SCA's
    // default of 10 applies: a worker would pull up to 10 envelopes at once and
    // park the extras on the PortalRateLimiter semaphore, where they burn their
    // visibility timeout waiting and get redelivered as EXPIRED_DUPLICATE.
    @Value("${spring.cloud.aws.sqs.listener.max-concurrent-messages:1}")
    private int maxConcurrentMessages;

    @Value("${spring.cloud.aws.sqs.listener.max-messages-per-poll:1}")
    private int maxMessagesPerPoll;

    @Bean
    TaskProtection taskProtection() {
        return EcsTaskProtectionClient.fromEnvironment();
    }

    /**
     * Override the default listener container factory to use FAIL strategy.
     * Prevents SCA from calling sqs:CreateQueue on startup for pre-provisioned queues.
     *
     * <p>Also registers {@link TaskProtectionInterceptor}, so a task is never
     * scaled in or replaced by a deploy while it is driving a portal.
     */
    @Bean
    SqsMessageListenerContainerFactory<Object> defaultSqsListenerContainerFactory(
            SqsAsyncClient sqsAsyncClient, TaskProtection taskProtection) {
        return SqsMessageListenerContainerFactory.builder()
                .configure(options -> options
                        .queueNotFoundStrategy(QueueNotFoundStrategy.FAIL)
                        .maxConcurrentMessages(maxConcurrentMessages)
                        .maxMessagesPerPoll(maxMessagesPerPoll))
                .messageInterceptor(new TaskProtectionInterceptor(taskProtection))
                .sqsAsyncClient(sqsAsyncClient)
                .build();
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
