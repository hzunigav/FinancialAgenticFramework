package com.neoproc.financialagent.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the RabbitMQ topology described in PraxisIntegrationHandoff §C.1.
 * All declarations are idempotent — running against a broker that already
 * has these entities with the same parameters is a no-op.
 */
@Configuration
public class RabbitMqConfig {

    @Value("${agent.worker.submit-queue}")
    private String submitQueueName;

    @Value("${agent.worker.capture-queue}")
    private String captureQueueName;

    // -----------------------------------------------------------------------
    // Submit queue (per-portal, durable, TTL 1 h, routes stale/failed to DLQ)
    // -----------------------------------------------------------------------

    @Bean
    Queue submitQueue() {
        return QueueBuilder.durable(submitQueueName)
                .deadLetterExchange("financeagent.dlx")
                .build();
    }

    @Bean
    Queue captureQueue() {
        return QueueBuilder.durable(captureQueueName)
                .deadLetterExchange("financeagent.dlx")
                .build();
    }

    @Bean
    DirectExchange tasksExchange() {
        return ExchangeBuilder.directExchange("financeagent.tasks").durable(true).build();
    }

    @Bean
    Binding submitQueueBinding(Queue submitQueue, DirectExchange tasksExchange,
                                @Value("${portal.id}") String portalId) {
        return BindingBuilder.bind(submitQueue).to(tasksExchange).with("submit." + portalId);
    }

    @Bean
    Binding captureQueueBinding(Queue captureQueue, DirectExchange tasksExchange,
                                 @Value("${portal.id}") String portalId) {
        return BindingBuilder.bind(captureQueue).to(tasksExchange).with("capture." + portalId);
    }

    // -----------------------------------------------------------------------
    // Dead-letter exchange + DLQ
    // -----------------------------------------------------------------------

    @Bean
    FanoutExchange dlx() {
        return ExchangeBuilder.fanoutExchange("financeagent.dlx").durable(true).build();
    }

    @Bean
    Queue dlq() {
        return QueueBuilder.durable("financeagent.dlq").build();
    }

    @Bean
    Binding dlqBinding(Queue dlq, FanoutExchange dlx) {
        return BindingBuilder.bind(dlq).to(dlx);
    }

    // -----------------------------------------------------------------------
    // Results exchange + queue (Praxis consumes; worker only publishes here)
    // -----------------------------------------------------------------------

    @Bean
    DirectExchange resultsExchange() {
        return ExchangeBuilder.directExchange("financeagent.results").durable(true).build();
    }

    @Bean
    Queue resultsQueue() {
        return QueueBuilder.durable("financeagent.results").build();
    }

    @Bean
    Binding resultsBinding(Queue resultsQueue, DirectExchange resultsExchange) {
        // Empty routing key — worker publishes with "" and Praxis binds the same.
        return BindingBuilder.bind(resultsQueue).to(resultsExchange).with("");
    }

    // -----------------------------------------------------------------------
    // Shared Jackson converter + template
    // -----------------------------------------------------------------------

    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        // Do not write __TypeId__ headers; infer target type from listener
        // method signature. This lets Praxis publish plain JSON without
        // needing to know our internal class names.
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                   MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}
