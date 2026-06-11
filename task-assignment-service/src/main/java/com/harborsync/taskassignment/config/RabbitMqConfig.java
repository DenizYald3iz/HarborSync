package com.harborsync.taskassignment.config;

import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE = "harborsync.exchange";
    public static final String CONGESTION_ALERT_ROUTING_KEY = "congestion.alert";
    public static final String CONGESTION_ALERT_QUEUE = "congestion.alert.task-assignment";
    public static final String TASK_CREATED_QUEUE = "task.created";
    public static final String TASK_FAILED_QUEUE = "task.failed";
    public static final String DLQ_ERRORS_QUEUE = "dlq.errors";

    @Bean
    public DirectExchange harborSyncExchange(@Value("${harborsync.rabbitmq.exchange:" + EXCHANGE + "}") String exchangeName) {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public Queue congestionAlertQueue() {
        return QueueBuilder.durable(CONGESTION_ALERT_QUEUE)
                .withArguments(deadLetterArguments())
                .build();
    }

    @Bean
    public Queue taskCreatedQueue() {
        return QueueBuilder.durable(TASK_CREATED_QUEUE)
                .withArguments(deadLetterArguments())
                .build();
    }

    @Bean
    public Queue taskFailedQueue() {
        return QueueBuilder.durable(TASK_FAILED_QUEUE)
                .withArguments(deadLetterArguments())
                .build();
    }

    @Bean
    public Queue dlqErrorsQueue() {
        return QueueBuilder.durable(DLQ_ERRORS_QUEUE).build();
    }

    @Bean
    public Binding congestionAlertBinding(Queue congestionAlertQueue, DirectExchange harborSyncExchange) {
        return BindingBuilder.bind(congestionAlertQueue)
                .to(harborSyncExchange)
                .with(CONGESTION_ALERT_ROUTING_KEY);
    }

    @Bean
    public Binding taskCreatedBinding(Queue taskCreatedQueue, DirectExchange harborSyncExchange) {
        return BindingBuilder.bind(taskCreatedQueue)
                .to(harborSyncExchange)
                .with(TASK_CREATED_QUEUE);
    }

    @Bean
    public Binding taskFailedBinding(Queue taskFailedQueue, DirectExchange harborSyncExchange) {
        return BindingBuilder.bind(taskFailedQueue)
                .to(harborSyncExchange)
                .with(TASK_FAILED_QUEUE);
    }

    @Bean
    public Binding dlqErrorsBinding(Queue dlqErrorsQueue, DirectExchange harborSyncExchange) {
        return BindingBuilder.bind(dlqErrorsQueue)
                .to(harborSyncExchange)
                .with(DLQ_ERRORS_QUEUE);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter);
        return rabbitTemplate;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    private Map<String, Object> deadLetterArguments() {
        return Map.of(
                "x-dead-letter-exchange", "",
                "x-dead-letter-routing-key", DLQ_ERRORS_QUEUE
        );
    }
}
