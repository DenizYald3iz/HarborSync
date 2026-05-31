package com.harborsync.congestionanalysis.messaging.consumer;

import com.harborsync.congestionanalysis.config.RabbitMqConfig;
import com.harborsync.congestionanalysis.dto.ProcessedTelemetryEvent;
import com.harborsync.congestionanalysis.messaging.producer.CongestionAlertProducer;
import com.harborsync.congestionanalysis.service.CongestionRuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class TelemetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConsumer.class);

    private final CongestionRuleEngine ruleEngine;
    private final CongestionAlertProducer alertProducer;

    public TelemetryConsumer(CongestionRuleEngine ruleEngine, CongestionAlertProducer alertProducer) {
        this.ruleEngine = ruleEngine;
        this.alertProducer = alertProducer;
    }

    @RabbitListener(queues = RabbitMqConfig.TELEMETRY_PROCESSED_QUEUE)
    public void onTelemetryReceived(ProcessedTelemetryEvent event) {
        MDC.put("correlationId", event == null ? "N/A" : event.correlationId());
        try {
            log.info("Received telemetry.processed sector={} fillRate={}",
                    event == null ? null : event.sector(),
                    event == null ? null : event.fillRate());

            ruleEngine.evaluate(event).ifPresent(alertProducer::publish);
        } finally {
            MDC.clear();
        }
    }
}
