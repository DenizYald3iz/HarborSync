package com.harborsync.telemetry.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class RawTelemetryPayload {

    @NotBlank
    private String droneId;

    @NotBlank
    private String sector;

    @Min(0)
    private int containerCount;

    @Min(1)
    private int capacity;

    private boolean blockageDetected;

    @NotBlank
    private String timestamp;

    private String vesselEta;

    public String getDroneId() {
        return droneId;
    }

    public void setDroneId(String droneId) {
        this.droneId = droneId;
    }

    public String getSector() {
        return sector;
    }

    public void setSector(String sector) {
        this.sector = sector;
    }

    public int getContainerCount() {
        return containerCount;
    }

    public void setContainerCount(int containerCount) {
        this.containerCount = containerCount;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public boolean isBlockageDetected() {
        return blockageDetected;
    }

    public void setBlockageDetected(boolean blockageDetected) {
        this.blockageDetected = blockageDetected;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getVesselEta() {
        return vesselEta;
    }

    public void setVesselEta(String vesselEta) {
        this.vesselEta = vesselEta;
    }
}
