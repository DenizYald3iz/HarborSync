package com.harborsync.vessel.dto;

import com.harborsync.vessel.domain.VesselStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateVesselStatusRequest(
        @NotNull VesselStatus status,
        @Size(max = 10) String berth
) {
}
