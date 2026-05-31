package com.harborsync.taskassignment.messaging.consumer;

import com.harborsync.taskassignment.config.RabbitMqConfig;
import com.harborsync.taskassignment.dto.CongestionAlertEvent;
import com.harborsync.taskassignment.service.TaskAssignmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class CongestionAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(CongestionAlertConsumer.class);

    private final TaskAssignmentService taskAssignmentService;

    public CongestionAlertConsumer(TaskAssignmentService taskAssignmentService) {
        this.taskAssignmentService = taskAssignmentService;
    }

    @RabbitListener(queues = RabbitMqConfig.CONGESTION_ALERT_QUEUE)
    public void onAlertReceived(CongestionAlertEvent alert) {
        MDC.put("correlationId", alert == null ? "N/A" : alert.correlationId());
        try {
            log.info("Received congestion.alert alertType={} sector={}",
                    alert == null ? null : alert.alertType(),
                    alert == null ? null : alert.sector());
            taskAssignmentService.handleAlert(alert);
        } finally {
            MDC.clear();
        }
    }
}

