package com.harborsync.telemetry.messaging.producer;

import com.harborsync.telemetry.dto.ProcessedTelemetryEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TelemetryProducer {

    private final RabbitTemplate rabbitTemplate;
    private final String exchangeName;
    private final String telemetryProcessedQueueName;

    public TelemetryProducer(
            RabbitTemplate rabbitTemplate,
            @Value("${harborsync.rabbitmq.exchange}") String exchangeName,
            @Value("${harborsync.rabbitmq.queues.telemetry-processed}") String telemetryProcessedQueueName) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchangeName = exchangeName;
        this.telemetryProcessedQueueName = telemetryProcessedQueueName;
    }

    public void publish(ProcessedTelemetryEvent event) {
        rabbitTemplate.convertAndSend(exchangeName, telemetryProcessedQueueName, event);
    }
}
