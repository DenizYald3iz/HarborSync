package com.harborsync.vessel.repository;

import com.harborsync.vessel.domain.Vessel;
import com.harborsync.vessel.domain.VesselStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VesselRepository extends JpaRepository<Vessel, UUID> {

    List<Vessel> findByStatus(VesselStatus status);

    Optional<Vessel> findByImoNumber(String imoNumber);
}
