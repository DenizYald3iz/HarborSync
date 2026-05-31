package com.harborsync.telemetry.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harborsync.telemetry.dto.ProcessedTelemetryEvent;
import com.harborsync.telemetry.exception.GlobalExceptionHandler;
import com.harborsync.telemetry.service.TelemetryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TelemetryController.class)
@Import(GlobalExceptionHandler.class)
class TelemetryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TelemetryService telemetryService;

    @Test
    void ingestUsesCorrelationHeaderAndReturnsAcceptedEvent() throws Exception {
        ProcessedTelemetryEvent event = new ProcessedTelemetryEvent();
        event.setCorrelationId("CID-42");
        event.setDroneId("HD-07");
        event.setSector("B-12");
        event.setFillRate(0.94);
        event.setBlockageDetected(true);
        event.setTimestamp("2025-01-15T14:28:00Z");
        event.setVesselEta("14:30");
        when(telemetryService.process(any(), eq("CID-42"))).thenReturn(event);

        mockMvc.perform(post("/telemetry/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-ID", "CID-42")
                .content(validPayload()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.correlationId").value("CID-42"))
            .andExpect(jsonPath("$.sector").value("B-12"))
            .andExpect(jsonPath("$.fillRate").value(0.94));

        verify(telemetryService).process(any(), eq("CID-42"));
    }

    @Test
    void ingestRejectsInvalidPayloadBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/telemetry/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "droneId": "",
                      "sector": "B-12",
                      "containerCount": 12,
                      "capacity": 0,
                      "blockageDetected": false,
                      "timestamp": "2025-01-15T14:28:00Z"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.droneId").exists())
            .andExpect(jsonPath("$.capacity").exists());

        verifyNoInteractions(telemetryService);
    }

    private String validPayload() {
        return """
            {
              "droneId": "HD-07",
              "sector": "B-12",
              "containerCount": 94,
              "capacity": 100,
              "blockageDetected": true,
              "timestamp": "2025-01-15T14:28:00Z",
              "vesselEta": "14:30"
            }
            """;
    }
}
