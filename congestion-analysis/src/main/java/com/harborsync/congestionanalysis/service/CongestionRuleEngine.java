package com.harborsync.congestionanalysis.service;

import com.harborsync.congestionanalysis.dto.CongestionAlertEvent;
import com.harborsync.congestionanalysis.dto.ProcessedTelemetryEvent;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CongestionRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(CongestionRuleEngine.class);

    private final double criticalThreshold;
    private final double warningThreshold;

    public CongestionRuleEngine(
            @Value("${harborsync.thresholds.critical:0.90}") double criticalThreshold,
            @Value("${harborsync.thresholds.warning:0.85}") double warningThreshold) {
        this.criticalThreshold = criticalThreshold;
        this.warningThreshold = warningThreshold;
    }

    public Optional<CongestionAlertEvent> evaluate(ProcessedTelemetryEvent event) {
        if (event == null) {
            return Optional.empty();
        }

        if (event.blockageDetected() && StringUtils.hasText(event.vesselEta())) {
            return Optional.of(buildAlert(event, "IMMEDIATE_ACTION", "HIGH", "HOLD_VESSEL"));
        }

        if (event.fillRate() > criticalThreshold) {
            return Optional.of(buildAlert(event, "SECTOR_CRITICAL", "HIGH", "REDIRECT_CRANE"));
        }

        if (event.fillRate() > warningThreshold) {
            return Optional.of(buildAlert(event, "SECTOR_WARNING", "MEDIUM", "MONITOR_SECTOR"));
        }

        log.debug("Sector {} is nominal fillRate={}", event.sector(), event.fillRate());
        return Optional.empty();
    }

    private CongestionAlertEvent buildAlert(
            ProcessedTelemetryEvent event,
            String alertType,
            String severity,
            String recommendedAction) {
        log.warn("Alert detected alertType={} sector={} fillRate={}",
                alertType, event.sector(), event.fillRate());

        return new CongestionAlertEvent(
                event.correlationId(),
                alertType,
                event.sector(),
                severity,
                event.fillRate(),
                recommendedAction,
                Instant.now().toString()
        );
    }
}
