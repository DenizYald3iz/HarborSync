package com.harborsync.telemetry.dto;

public class ProcessedTelemetryEvent {

    private String correlationId;
    private String sector;
    private double fillRate;
    private boolean blockageDetected;
    private String droneId;
    private String vesselEta;
    private String timestamp;

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getSector() {
        return sector;
    }

    public void setSector(String sector) {
        this.sector = sector;
    }

    public double getFillRate() {
        return fillRate;
    }

    public void setFillRate(double fillRate) {
        this.fillRate = fillRate;
    }

    public boolean isBlockageDetected() {
        return blockageDetected;
    }

    public void setBlockageDetected(boolean blockageDetected) {
        this.blockageDetected = blockageDetected;
    }

    public String getDroneId() {
        return droneId;
    }

    public void setDroneId(String droneId) {
        this.droneId = droneId;
    }

    public String getVesselEta() {
        return vesselEta;
    }

    public void setVesselEta(String vesselEta) {
        this.vesselEta = vesselEta;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
