package com.harborsync.vessel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BerthReserveRequest(
        @NotBlank @Size(max = 10) String berth
) {
}
