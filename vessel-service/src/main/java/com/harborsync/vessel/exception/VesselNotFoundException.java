package com.harborsync.vessel.exception;

import java.util.UUID;

public class VesselNotFoundException extends RuntimeException {

    public VesselNotFoundException(UUID id) {
        super("Vessel not found: " + id);
    }
}
