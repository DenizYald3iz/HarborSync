package com.harborsync.vessel.dto;

import com.harborsync.vessel.domain.VesselStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

public record VesselLifecycleEvent(
        String correlationId,
        UUID vesselId,
        String name,
        String imoNumber,
        VesselStatus status,
        String berth,
        LocalDateTime eta,
        Instant timestamp
) {
}
