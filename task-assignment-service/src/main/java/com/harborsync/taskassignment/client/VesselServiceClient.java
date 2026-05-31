package com.harborsync.taskassignment.client;

import com.harborsync.taskassignment.dto.VesselResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class VesselServiceClient {

    private static final Logger log = LoggerFactory.getLogger(VesselServiceClient.class);

    private final WebClient vesselServiceClient;

    public VesselServiceClient(@Qualifier("vesselWebClient") WebClient vesselServiceClient) {
        this.vesselServiceClient = vesselServiceClient;
    }

    @CircuitBreaker(name = "vessel-service", fallbackMethod = "getArrivingVesselsFallback")
    public List<VesselResponse> getArrivingVessels() {
        List<VesselResponse> vessels = vesselServiceClient.get()
                .uri("/vessels?status=ARRIVING")
                .retrieve()
                .bodyToFlux(VesselResponse.class)
                .collectList()
                .block();
        return vessels == null ? List.of() : vessels;
    }

    public List<VesselResponse> getArrivingVesselsFallback(Throwable throwable) {
        log.warn("Vessel Service unavailable, continuing with empty vessel list. reason={}", throwable.getMessage());
        return List.of();
    }
}
