package com.harborsync.vessel.messaging.producer;

import com.harborsync.vessel.config.RabbitMqConfig;
import com.harborsync.vessel.domain.Vessel;
import com.harborsync.vessel.domain.VesselStatus;
import com.harborsync.vessel.dto.VesselLifecycleEvent;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class VesselLifecycleProducer {

    private static final Logger log = LoggerFactory.getLogger(VesselLifecycleProducer.class);

    private final RabbitTemplate rabbitTemplate;

    public VesselLifecycleProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(Vessel vessel) {
        String routingKey = routingKeyFor(vessel.getStatus());
        if (routingKey == null) {
            return;
        }

        VesselLifecycleEvent event = new VesselLifecycleEvent(
                MDC.get("correlationId"),
                vessel.getId(),
                vessel.getName(),
                vessel.getImoNumber(),
                vessel.getStatus(),
                vessel.getBerth(),
                vessel.getEta(),
                Instant.now()
        );

        rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, routingKey, event);
        log.info("Published {} vesselId={} imoNumber={}", routingKey, vessel.getId(), vessel.getImoNumber());
    }

    private String routingKeyFor(VesselStatus status) {
        return switch (status) {
            case ARRIVING -> RabbitMqConfig.VESSEL_ARRIVED_ROUTING_KEY;
            case DOCKED -> RabbitMqConfig.VESSEL_DOCKED_ROUTING_KEY;
            case DEPARTED -> RabbitMqConfig.VESSEL_DEPARTED_ROUTING_KEY;
            case BERTHED, DEPARTING -> null;
        };
    }
}
