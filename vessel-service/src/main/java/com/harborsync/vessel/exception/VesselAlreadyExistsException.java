package com.harborsync.vessel.exception;

public class VesselAlreadyExistsException extends RuntimeException {

    public VesselAlreadyExistsException(String imoNumber) {
        super("Vessel already exists with IMO number: " + imoNumber);
    }
}
