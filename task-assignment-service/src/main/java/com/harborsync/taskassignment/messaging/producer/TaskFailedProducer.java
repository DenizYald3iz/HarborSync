package com.harborsync.taskassignment.messaging.producer;

import com.harborsync.taskassignment.config.RabbitMqConfig;
import com.harborsync.taskassignment.dto.TaskFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class TaskFailedProducer {

    private static final Logger log = LoggerFactory.getLogger(TaskFailedProducer.class);

    private final RabbitTemplate rabbitTemplate;

    public TaskFailedProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(TaskFailedEvent event) {
        rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, RabbitMqConfig.TASK_FAILED_QUEUE, event);
        log.info("Published task.failed taskId={} correlationId={} reason={}",
                event.taskId(), event.correlationId(), event.reason());
    }
}
