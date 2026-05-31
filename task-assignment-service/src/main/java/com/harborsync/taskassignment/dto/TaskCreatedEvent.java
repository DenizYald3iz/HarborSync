package com.harborsync.taskassignment.dto;

import java.time.Instant;
import java.util.UUID;

public record TaskCreatedEvent(
        String correlationId,
        UUID taskId,
        String sector,
        String assignedUnit,
        String priority,
        Instant timestamp
) {
}

