package com.harborsync.taskassignment.dto;

import java.time.Instant;

public record CongestionAlertEvent(
        String correlationId,
        String alertType,
        String sector,
        String severity,
        Double fillRate,
        String recommendedAction,
        Instant timestamp
) {
}

