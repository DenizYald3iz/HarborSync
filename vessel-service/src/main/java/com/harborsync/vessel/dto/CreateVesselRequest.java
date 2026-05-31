package com.harborsync.vessel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record CreateVesselRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 20) String imoNumber,
        @Size(max = 10) String berth,
        LocalDateTime eta
) {
}
