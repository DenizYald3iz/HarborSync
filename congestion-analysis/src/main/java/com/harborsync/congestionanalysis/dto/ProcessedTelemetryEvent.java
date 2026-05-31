package com.harborsync.congestionanalysis.dto;

public record ProcessedTelemetryEvent(
        String correlationId,
        String sector,
        double fillRate,
        boolean blockageDetected,
        String droneId,
        String vesselEta,
        String timestamp
) {
}
