package com.harborsync.taskassignment.dto;

import java.time.Instant;
import java.util.UUID;

public record TaskFailedEvent(
        String correlationId,
        UUID taskId,
        String sector,
        String reason,
        Instant timestamp
) {
}
