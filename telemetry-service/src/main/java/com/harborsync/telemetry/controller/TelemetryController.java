package com.harborsync.telemetry.controller;

import com.harborsync.telemetry.dto.ProcessedTelemetryEvent;
import com.harborsync.telemetry.dto.RawTelemetryPayload;
import com.harborsync.telemetry.service.TelemetryService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/telemetry")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProcessedTelemetryEvent ingest(
            @Valid @RequestBody RawTelemetryPayload payload,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        String effectiveCorrelationId = correlationId == null || correlationId.isBlank()
            ? UUID.randomUUID().toString()
            : correlationId;
        MDC.put("correlationId", effectiveCorrelationId);
        try {
            return telemetryService.process(payload, effectiveCorrelationId);
        } finally {
            MDC.clear();
        }
    }
}
