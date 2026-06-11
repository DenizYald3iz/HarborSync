package com.harborsync.vessel.controller;

import com.harborsync.vessel.domain.VesselStatus;
import com.harborsync.vessel.dto.BerthReserveRequest;
import com.harborsync.vessel.dto.CreateVesselRequest;
import com.harborsync.vessel.dto.UpdateVesselStatusRequest;
import com.harborsync.vessel.dto.VesselResponse;
import com.harborsync.vessel.service.VesselService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/vessels")
public class VesselController {

    private final VesselService vesselService;

    public VesselController(VesselService vesselService) {
        this.vesselService = vesselService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VesselResponse register(@Valid @RequestBody CreateVesselRequest request) {
        return vesselService.register(request);
    }

    @GetMapping
    public List<VesselResponse> list(@RequestParam(required = false) VesselStatus status) {
        if (status == null) {
            return vesselService.listAll();
        }
        return vesselService.listByStatus(status);
    }

    @GetMapping("/{id}")
    public VesselResponse getById(@PathVariable UUID id) {
        return vesselService.getById(id);
    }

    @PutMapping("/{id}/status")
    public VesselResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateVesselStatusRequest request) {
        return vesselService.updateStatus(id, request);
    }

    @PutMapping("/{id}/berth/reserve")
    @ResponseStatus(HttpStatus.OK)
    public VesselResponse reserveBerth(
            @PathVariable UUID id,
            @Valid @RequestBody BerthReserveRequest request) {
        return vesselService.reserveBerth(id, request);
    }

    @PutMapping("/{id}/berth/release")
    @ResponseStatus(HttpStatus.OK)
    public VesselResponse releaseBerth(@PathVariable UUID id) {
        return vesselService.releaseBerth(id);
    }
}
