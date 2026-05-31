package com.harborsync.taskassignment.messaging.producer;

import com.harborsync.taskassignment.config.RabbitMqConfig;
import com.harborsync.taskassignment.dto.TaskCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class TaskCreatedProducer {

    private static final Logger log = LoggerFactory.getLogger(TaskCreatedProducer.class);

    private final RabbitTemplate rabbitTemplate;

    public TaskCreatedProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(TaskCreatedEvent event) {
        rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, RabbitMqConfig.TASK_CREATED_QUEUE, event);
        log.info("Published task.created taskId={} correlationId={}", event.taskId(), event.correlationId());
    }
}

