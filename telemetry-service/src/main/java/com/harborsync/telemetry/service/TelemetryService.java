package com.harborsync.telemetry.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harborsync.telemetry.dto.ProcessedTelemetryEvent;
import com.harborsync.telemetry.dto.RawTelemetryPayload;
import com.harborsync.telemetry.messaging.producer.TelemetryProducer;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final TelemetryProducer telemetryProducer;
    private final ObjectMapper objectMapper;
    private final long droneTtlSeconds;

    public TelemetryService(
            RedisTemplate<String, String> redisTemplate,
            TelemetryProducer telemetryProducer,
            ObjectMapper objectMapper,
            @Value("${harborsync.redis.drone-state-ttl-seconds}") long droneTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.telemetryProducer = telemetryProducer;
        this.objectMapper = objectMapper;
        this.droneTtlSeconds = droneTtlSeconds;
    }

    public ProcessedTelemetryEvent process(RawTelemetryPayload payload, String correlationId) {
        if (payload.getCapacity() <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0 for drone " + payload.getDroneId());
        }

        double fillRate = (double) payload.getContainerCount() / payload.getCapacity();
        writeLatestDroneState(payload, correlationId);

        ProcessedTelemetryEvent event = new ProcessedTelemetryEvent();
        event.setCorrelationId(correlationId);
        event.setSector(payload.getSector());
        event.setFillRate(fillRate);
        event.setBlockageDetected(payload.isBlockageDetected());
        event.setDroneId(payload.getDroneId());
        event.setVesselEta(payload.getVesselEta());
        event.setTimestamp(payload.getTimestamp());

        telemetryProducer.publish(event);
        log.info("Telemetry processed: drone={} sector={} fillRate={}", payload.getDroneId(), payload.getSector(), fillRate);
        return event;
    }

    private void writeLatestDroneState(RawTelemetryPayload payload, String correlationId) {
        String redisKey = "drone:" + payload.getDroneId();
        try {
            String stateJson = objectMapper.writeValueAsString(payload);
            redisTemplate.opsForValue().set(redisKey, stateJson, droneTtlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("[{}] Redis serialization failed for drone {}: {}", correlationId, payload.getDroneId(), e.getMessage());
        }
    }
}
