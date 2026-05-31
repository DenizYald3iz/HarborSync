package com.harborsync.taskassignment.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record VesselResponse(
        UUID id,
        String name,
        String status,
        String berth,
        LocalDateTime eta
) {
}

