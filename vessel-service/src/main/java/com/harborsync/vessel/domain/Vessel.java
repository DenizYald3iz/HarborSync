package com.harborsync.vessel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "vessels")
public class Vessel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "imo_number", nullable = false, unique = true, length = 20)
    private String imoNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VesselStatus status = VesselStatus.ARRIVING;

    @Column(length = 10)
    private String berth;

    private LocalDateTime eta;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getImoNumber() {
        return imoNumber;
    }

    public void setImoNumber(String imoNumber) {
        this.imoNumber = imoNumber;
    }

    public VesselStatus getStatus() {
        return status;
    }

    public void setStatus(VesselStatus status) {
        this.status = status;
    }

    public String getBerth() {
        return berth;
    }

    public void setBerth(String berth) {
        this.berth = berth;
    }

    public LocalDateTime getEta() {
        return eta;
    }

    public void setEta(LocalDateTime eta) {
        this.eta = eta;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
