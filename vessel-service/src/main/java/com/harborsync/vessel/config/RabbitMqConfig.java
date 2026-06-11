package com.harborsync.vessel.config;

import org.springframework.amqp.core.DirectExchange;
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
    public static final String VESSEL_ARRIVED_ROUTING_KEY = "vessel.arrived";
    public static final String VESSEL_DOCKED_ROUTING_KEY = "vessel.docked";
    public static final String VESSEL_DEPARTED_ROUTING_KEY = "vessel.departed";

    @Bean
    public DirectExchange harborSyncExchange(@Value("${harborsync.rabbitmq.exchange:" + EXCHANGE + "}") String exchangeName) {
        return new DirectExchange(exchangeName, true, false);
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
}
