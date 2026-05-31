package com.harborsync.telemetry.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${harborsync.rabbitmq.exchange}")
    private String exchangeName;

    @Value("${harborsync.rabbitmq.queues.telemetry-processed}")
    private String telemetryProcessedQueueName;

    @Value("${harborsync.rabbitmq.queues.dlq}")
    private String deadLetterQueueName;

    @Bean
    public DirectExchange harborSyncExchange() {
        return new DirectExchange(exchangeName);
    }

    @Bean
    public Queue telemetryProcessedQueue() {
        return QueueBuilder.durable(telemetryProcessedQueueName)
            .withArgument("x-dead-letter-exchange", "")
            .withArgument("x-dead-letter-routing-key", deadLetterQueueName)
            .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(deadLetterQueueName).build();
    }

    @Bean
    public Binding telemetryProcessedBinding(Queue telemetryProcessedQueue, DirectExchange harborSyncExchange) {
        return BindingBuilder.bind(telemetryProcessedQueue)
            .to(harborSyncExchange)
            .with(telemetryProcessedQueueName);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }
}
