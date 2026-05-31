package com.harborsync.vessel.dto;

import com.harborsync.vessel.domain.VesselStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record VesselResponse(
        UUID id,
        String name,
        String imoNumber,
        VesselStatus status,
        String berth,
        LocalDateTime eta,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
