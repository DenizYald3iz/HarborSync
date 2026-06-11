package com.harborsync.vessel.config;

import java.util.Map;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE = "harborsync.exchange";
    public static final String VESSEL_ARRIVED_ROUTING_KEY = "vessel.arrived";
    public static final String VESSEL_DOCKED_ROUTING_KEY = "vessel.docked";
    public static final String VESSEL_DEPARTED_ROUTING_KEY = "vessel.departed";
    public static final String DLQ_ERRORS_QUEUE = "dlq.errors";

    @Bean
    public DirectExchange harborSyncExchange(@Value("${harborsync.rabbitmq.exchange:" + EXCHANGE + "}") String exchangeName) {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public Queue vesselArrivedQueue() {
        return QueueBuilder.durable(VESSEL_ARRIVED_ROUTING_KEY)
                .withArguments(deadLetterArguments())
                .build();
    }

    @Bean
    public Queue vesselDockedQueue() {
        return QueueBuilder.durable(VESSEL_DOCKED_ROUTING_KEY)
                .withArguments(deadLetterArguments())
                .build();
    }

    @Bean
    public Queue vesselDepartedQueue() {
        return QueueBuilder.durable(VESSEL_DEPARTED_ROUTING_KEY)
                .withArguments(deadLetterArguments())
                .build();
    }

    @Bean
    public Queue dlqErrorsQueue() {
        return QueueBuilder.durable(DLQ_ERRORS_QUEUE).build();
    }

    @Bean
    public Binding vesselArrivedBinding(
            @Qualifier("vesselArrivedQueue") Queue vesselArrivedQueue,
            DirectExchange harborSyncExchange) {
        return BindingBuilder.bind(vesselArrivedQueue)
                .to(harborSyncExchange)
                .with(VESSEL_ARRIVED_ROUTING_KEY);
    }

    @Bean
    public Binding vesselDockedBinding(
            @Qualifier("vesselDockedQueue") Queue vesselDockedQueue,
            DirectExchange harborSyncExchange) {
        return BindingBuilder.bind(vesselDockedQueue)
                .to(harborSyncExchange)
                .with(VESSEL_DOCKED_ROUTING_KEY);
    }

    @Bean
    public Binding vesselDepartedBinding(
            @Qualifier("vesselDepartedQueue") Queue vesselDepartedQueue,
            DirectExchange harborSyncExchange) {
        return BindingBuilder.bind(vesselDepartedQueue)
                .to(harborSyncExchange)
                .with(VESSEL_DEPARTED_ROUTING_KEY);
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

    private Map<String, Object> deadLetterArguments() {
        return Map.of(
                "x-dead-letter-exchange", "",
                "x-dead-letter-routing-key", DLQ_ERRORS_QUEUE
        );
    }
}
