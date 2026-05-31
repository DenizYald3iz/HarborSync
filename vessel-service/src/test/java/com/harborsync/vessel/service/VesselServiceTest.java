package com.harborsync.vessel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harborsync.vessel.domain.Vessel;
import com.harborsync.vessel.domain.VesselStatus;
import com.harborsync.vessel.dto.CreateVesselRequest;
import com.harborsync.vessel.dto.UpdateVesselStatusRequest;
import com.harborsync.vessel.dto.VesselResponse;
import com.harborsync.vessel.exception.VesselAlreadyExistsException;
import com.harborsync.vessel.exception.VesselNotFoundException;
import com.harborsync.vessel.repository.VesselRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VesselServiceTest {

    @Mock
    private VesselRepository vesselRepository;

    @InjectMocks
    private VesselService vesselService;

    @Test
    void registerCreatesArrivingVessel() {
        LocalDateTime eta = LocalDateTime.of(2026, 6, 1, 10, 30);
        when(vesselRepository.findByImoNumber("IMO1234567")).thenReturn(Optional.empty());
        when(vesselRepository.save(any(Vessel.class))).thenAnswer(invocation -> {
            Vessel vessel = invocation.getArgument(0);
            vessel.setId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
            return vessel;
        });

        VesselResponse response = vesselService.register(
                new CreateVesselRequest("Mavi Deniz", "IMO1234567", "A1", eta));

        assertThat(response.id()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(response.status()).isEqualTo(VesselStatus.ARRIVING);
        assertThat(response.berth()).isEqualTo("A1");
        assertThat(response.eta()).isEqualTo(eta);
    }

    @Test
    void registerRejectsDuplicateImoNumber() {
        Vessel existing = vessel("550e8400-e29b-41d4-a716-446655440001", VesselStatus.ARRIVING);
        existing.setImoNumber("IMO1234567");
        when(vesselRepository.findByImoNumber("IMO1234567")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> vesselService.register(
                new CreateVesselRequest("Mavi Deniz", "IMO1234567", null, null)))
                .isInstanceOf(VesselAlreadyExistsException.class)
                .hasMessageContaining("IMO1234567");
    }

    @Test
    void listByStatusUsesRepositoryFilter() {
        Vessel vessel = vessel("550e8400-e29b-41d4-a716-446655440002", VesselStatus.DOCKED);
        when(vesselRepository.findByStatus(VesselStatus.DOCKED)).thenReturn(List.of(vessel));

        List<VesselResponse> vessels = vesselService.listByStatus(VesselStatus.DOCKED);

        assertThat(vessels).hasSize(1);
        assertThat(vessels.get(0).status()).isEqualTo(VesselStatus.DOCKED);
        verify(vesselRepository).findByStatus(VesselStatus.DOCKED);
    }

    @Test
    void updateStatusChangesStatusAndBerth() {
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");
        Vessel vessel = vessel(id.toString(), VesselStatus.ARRIVING);
        when(vesselRepository.findById(id)).thenReturn(Optional.of(vessel));
        when(vesselRepository.save(vessel)).thenReturn(vessel);

        VesselResponse response = vesselService.updateStatus(
                id, new UpdateVesselStatusRequest(VesselStatus.DOCKED, "B2"));

        assertThat(response.status()).isEqualTo(VesselStatus.DOCKED);
        assertThat(response.berth()).isEqualTo("B2");
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440004");
        when(vesselRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vesselService.getById(id))
                .isInstanceOf(VesselNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    private Vessel vessel(String id, VesselStatus status) {
        Vessel vessel = new Vessel();
        vessel.setId(UUID.fromString(id));
        vessel.setName("Mavi Deniz");
        vessel.setImoNumber("IMO7654321");
        vessel.setStatus(status);
        return vessel;
    }
}
