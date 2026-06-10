package com.harborsync.taskassignment.dto;

public record CongestionAlertEvent(
        String correlationId,
        String alertType,
        String sector,
        String severity,
        double fillRate,
        String recommendedAction,
        String timestamp
) {
}

