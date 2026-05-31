package com.harborsync.vessel.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harborsync.vessel.config.CorrelationIdFilter;
import com.harborsync.vessel.domain.VesselStatus;
import com.harborsync.vessel.dto.CreateVesselRequest;
import com.harborsync.vessel.dto.UpdateVesselStatusRequest;
import com.harborsync.vessel.dto.VesselResponse;
import com.harborsync.vessel.exception.GlobalExceptionHandler;
import com.harborsync.vessel.exception.VesselNotFoundException;
import com.harborsync.vessel.service.VesselService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VesselController.class)
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class})
class VesselControllerTest {

    private static final UUID VESSEL_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private VesselService vesselService;

    @Test
    void registerReturnsCreatedVessel() throws Exception {
        CreateVesselRequest request = new CreateVesselRequest("Mavi Deniz", "IMO1234567", "A1", null);
        when(vesselService.register(request)).thenReturn(response(VesselStatus.ARRIVING, "A1"));

        mockMvc.perform(post("/vessels")
                        .header(CorrelationIdFilter.CORRELATION_HEADER, "corr-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string(CorrelationIdFilter.CORRELATION_HEADER, "corr-123"))
                .andExpect(jsonPath("$.id").value(VESSEL_ID.toString()))
                .andExpect(jsonPath("$.status").value("ARRIVING"));
    }

    @Test
    void registerRejectsInvalidPayload() throws Exception {
        CreateVesselRequest request = new CreateVesselRequest("", "", null, null);

        mockMvc.perform(post("/vessels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fields.name").exists())
                .andExpect(jsonPath("$.fields.imoNumber").exists());
    }

    @Test
    void listFiltersByStatus() throws Exception {
        when(vesselService.listByStatus(VesselStatus.DOCKED))
                .thenReturn(List.of(response(VesselStatus.DOCKED, "B2")));

        mockMvc.perform(get("/vessels").param("status", "DOCKED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("DOCKED"))
                .andExpect(jsonPath("$[0].berth").value("B2"));

        verify(vesselService).listByStatus(VesselStatus.DOCKED);
    }

    @Test
    void getByIdReturnsNotFoundForMissingVessel() throws Exception {
        when(vesselService.getById(VESSEL_ID)).thenThrow(new VesselNotFoundException(VESSEL_ID));

        mockMvc.perform(get("/vessels/{id}", VESSEL_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void updateStatusDelegatesToService() throws Exception {
        UpdateVesselStatusRequest request = new UpdateVesselStatusRequest(VesselStatus.DEPARTING, "C3");
        when(vesselService.updateStatus(VESSEL_ID, request))
                .thenReturn(response(VesselStatus.DEPARTING, "C3"));

        mockMvc.perform(put("/vessels/{id}/status", VESSEL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPARTING"))
                .andExpect(jsonPath("$.berth").value("C3"));
    }

    private VesselResponse response(VesselStatus status, String berth) {
        return new VesselResponse(VESSEL_ID, "Mavi Deniz", "IMO1234567", status, berth, null, null, null);
    }
}
