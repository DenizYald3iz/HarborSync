package com.harborsync.taskassignment.messaging.producer;

import static org.mockito.Mockito.verify;

import com.harborsync.taskassignment.config.RabbitMqConfig;
import com.harborsync.taskassignment.dto.TaskCreatedEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class TaskCreatedProducerTest {

    private final RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
    private final TaskCreatedProducer producer = new TaskCreatedProducer(rabbitTemplate);

    @Test
    void publishRoutesTaskCreatedEventToConfiguredExchangeAndRoutingKey() {
        TaskCreatedEvent event = new TaskCreatedEvent(
                "CID-9",
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "B-12",
                "Crane-1",
                "HIGH",
                Instant.parse("2025-01-15T14:28:03Z")
        );

        producer.publish(event);

        verify(rabbitTemplate).convertAndSend(
                RabbitMqConfig.EXCHANGE,
                RabbitMqConfig.TASK_CREATED_QUEUE,
                event
        );
    }
}
