package com.harborsync.telemetry.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harborsync.telemetry.dto.ProcessedTelemetryEvent;
import com.harborsync.telemetry.dto.RawTelemetryPayload;
import com.harborsync.telemetry.messaging.producer.TelemetryProducer;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class TelemetryServiceTest {

    private final RedisTemplate<String, String> redisTemplate = Mockito.mock(RedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
    private final TelemetryProducer telemetryProducer = Mockito.mock(TelemetryProducer.class);
    private final TelemetryService telemetryService = new TelemetryService(
        redisTemplate,
        telemetryProducer,
        new ObjectMapper(),
        30
    );

    @Test
    void processComputesFillRateStoresRedisStateAndPublishesEvent() {
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RawTelemetryPayload payload = payload(94, 100);

        ProcessedTelemetryEvent event = telemetryService.process(payload, "CID-1");

        assertEquals(0.94, event.getFillRate(), 0.0001);
        assertEquals("CID-1", event.getCorrelationId());
        verify(valueOperations).set(eq("drone:HD-07"), any(String.class), eq(30L), eq(TimeUnit.SECONDS));
        verify(telemetryProducer).publish(any(ProcessedTelemetryEvent.class));
    }

    @Test
    void processRejectsZeroCapacity() {
        RawTelemetryPayload payload = payload(10, 0);

        assertThrows(IllegalArgumentException.class, () -> telemetryService.process(payload, "CID-2"));
    }

    private RawTelemetryPayload payload(int containerCount, int capacity) {
        RawTelemetryPayload payload = new RawTelemetryPayload();
        payload.setDroneId("HD-07");
        payload.setSector("B-12");
        payload.setContainerCount(containerCount);
        payload.setCapacity(capacity);
        payload.setBlockageDetected(true);
        payload.setTimestamp("2025-01-15T14:28:00Z");
        payload.setVesselEta("14:30");
        return payload;
    }
}
