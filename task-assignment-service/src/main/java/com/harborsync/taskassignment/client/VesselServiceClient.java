package com.harborsync.taskassignment.client;

import com.harborsync.taskassignment.dto.VesselResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
                .block(Duration.ofSeconds(10));
        return vessels == null ? List.of() : vessels;
    }

    public List<VesselResponse> getArrivingVesselsFallback(Throwable throwable) {
        log.warn("Vessel Service unavailable, continuing with empty vessel list. reason={}", throwable.getMessage());
        return List.of();
    }

    public VesselResponse reserveBerth(UUID vesselId, String berth) {
        log.info("Reserving berth vesselId={} berth={}", vesselId, berth);
        return vesselServiceClient.put()
                .uri("/vessels/{id}/berth/reserve", vesselId)
                .bodyValue(Map.of("berth", berth))
                .retrieve()
                .bodyToMono(VesselResponse.class)
                .block(Duration.ofSeconds(10));
    }

    public void releaseBerth(UUID vesselId) {
        log.info("Releasing berth vesselId={}", vesselId);
        vesselServiceClient.put()
                .uri("/vessels/{id}/berth/release", vesselId)
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(10));
    }
}
