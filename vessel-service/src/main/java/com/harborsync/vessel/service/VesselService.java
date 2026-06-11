package com.harborsync.vessel.service;

import com.harborsync.vessel.domain.Vessel;
import com.harborsync.vessel.domain.VesselStatus;
import com.harborsync.vessel.dto.BerthReserveRequest;
import com.harborsync.vessel.dto.CreateVesselRequest;
import com.harborsync.vessel.dto.UpdateVesselStatusRequest;
import com.harborsync.vessel.dto.VesselResponse;
import com.harborsync.vessel.exception.VesselAlreadyExistsException;
import com.harborsync.vessel.exception.VesselNotFoundException;
import com.harborsync.vessel.messaging.producer.VesselLifecycleProducer;
import com.harborsync.vessel.repository.VesselRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VesselService {

    private static final Logger log = LoggerFactory.getLogger(VesselService.class);

    private final VesselRepository vesselRepository;
    private final VesselLifecycleProducer lifecycleProducer;

    public VesselService(VesselRepository vesselRepository, VesselLifecycleProducer lifecycleProducer) {
        this.vesselRepository = vesselRepository;
        this.lifecycleProducer = lifecycleProducer;
    }

    @Transactional
    public VesselResponse register(CreateVesselRequest request) {
        vesselRepository.findByImoNumber(request.imoNumber())
                .ifPresent(existing -> {
                    throw new VesselAlreadyExistsException(existing.getImoNumber());
                });

        Vessel vessel = new Vessel();
        vessel.setName(request.name());
        vessel.setImoNumber(request.imoNumber());
        vessel.setBerth(request.berth());
        vessel.setEta(request.eta());
        vessel.setStatus(VesselStatus.ARRIVING);

        Vessel saved = vesselRepository.save(vessel);
        log.info("Vessel registered id={} imoNumber={} status={}",
                saved.getId(), saved.getImoNumber(), saved.getStatus());
        lifecycleProducer.publish(saved);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<VesselResponse> listAll() {
        return vesselRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<VesselResponse> listByStatus(VesselStatus status) {
        return vesselRepository.findByStatus(status).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public VesselResponse getById(UUID id) {
        return vesselRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new VesselNotFoundException(id));
    }

    @Transactional
    public VesselResponse updateStatus(UUID id, UpdateVesselStatusRequest request) {
        Vessel vessel = vesselRepository.findById(id)
                .orElseThrow(() -> new VesselNotFoundException(id));

        vessel.setStatus(request.status());
        if (request.berth() != null) {
            vessel.setBerth(request.berth());
        }

        Vessel saved = vesselRepository.save(vessel);
        log.info("Vessel status updated id={} status={} berth={}",
                saved.getId(), saved.getStatus(), saved.getBerth());
        lifecycleProducer.publish(saved);
        return toResponse(saved);
    }

    @Transactional
    public VesselResponse reserveBerth(UUID id, BerthReserveRequest request) {
        Vessel vessel = vesselRepository.findById(id)
                .orElseThrow(() -> new VesselNotFoundException(id));

        vessel.setStatus(VesselStatus.BERTHED);
        vessel.setBerth(request.berth());

        Vessel saved = vesselRepository.save(vessel);
        log.info("Berth reserved id={} berth={} status={}",
                saved.getId(), saved.getBerth(), saved.getStatus());
        lifecycleProducer.publish(saved);
        return toResponse(saved);
    }

    @Transactional
    public VesselResponse releaseBerth(UUID id) {
        Vessel vessel = vesselRepository.findById(id)
                .orElseThrow(() -> new VesselNotFoundException(id));

        vessel.setStatus(VesselStatus.ARRIVING);
        vessel.setBerth(null);

        Vessel saved = vesselRepository.save(vessel);
        log.info("Berth released id={} status={}",
                saved.getId(), saved.getStatus());
        lifecycleProducer.publish(saved);
        return toResponse(saved);
    }

    private VesselResponse toResponse(Vessel vessel) {
        return new VesselResponse(
                vessel.getId(),
                vessel.getName(),
                vessel.getImoNumber(),
                vessel.getStatus(),
                vessel.getBerth(),
                vessel.getEta(),
                vessel.getCreatedAt(),
                vessel.getUpdatedAt()
        );
    }
}
