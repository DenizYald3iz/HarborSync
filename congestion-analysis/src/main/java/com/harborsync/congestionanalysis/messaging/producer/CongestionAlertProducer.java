package com.harborsync.congestionanalysis.messaging.producer;

import com.harborsync.congestionanalysis.config.RabbitMqConfig;
import com.harborsync.congestionanalysis.dto.CongestionAlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class CongestionAlertProducer {

    private static final Logger log = LoggerFactory.getLogger(CongestionAlertProducer.class);

    private final RabbitTemplate rabbitTemplate;

    public CongestionAlertProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(CongestionAlertEvent alert) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.EXCHANGE,
                RabbitMqConfig.CONGESTION_ALERT_QUEUE,
                alert
        );
        log.info("Published congestion.alert alertType={} sector={} correlationId={}",
                alert.alertType(), alert.sector(), alert.correlationId());
    }
}
