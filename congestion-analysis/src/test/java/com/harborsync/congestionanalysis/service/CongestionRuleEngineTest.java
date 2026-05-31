package com.harborsync.congestionanalysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.harborsync.congestionanalysis.dto.CongestionAlertEvent;
import com.harborsync.congestionanalysis.dto.ProcessedTelemetryEvent;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CongestionRuleEngineTest {

    private final CongestionRuleEngine ruleEngine = new CongestionRuleEngine(0.90, 0.85);

    @Test
    void returnsCriticalAlertWhenFillRateExceedsCriticalThreshold() {
        Optional<CongestionAlertEvent> result = ruleEngine.evaluate(event(0.91, false, null));

        assertThat(result).isPresent();
        assertThat(result.get().alertType()).isEqualTo("SECTOR_CRITICAL");
        assertThat(result.get().severity()).isEqualTo("HIGH");
        assertThat(result.get().recommendedAction()).isEqualTo("REDIRECT_CRANE");
    }

    @Test
    void returnsWarningAlertWhenFillRateExceedsWarningThreshold() {
        Optional<CongestionAlertEvent> result = ruleEngine.evaluate(event(0.86, false, null));

        assertThat(result).isPresent();
        assertThat(result.get().alertType()).isEqualTo("SECTOR_WARNING");
        assertThat(result.get().severity()).isEqualTo("MEDIUM");
        assertThat(result.get().recommendedAction()).isEqualTo("MONITOR_SECTOR");
    }

    @Test
    void returnsImmediateActionWhenBlockageAndVesselEtaArePresent() {
        Optional<CongestionAlertEvent> result = ruleEngine.evaluate(event(0.70, true, "14:30"));

        assertThat(result).isPresent();
        assertThat(result.get().alertType()).isEqualTo("IMMEDIATE_ACTION");
        assertThat(result.get().severity()).isEqualTo("HIGH");
        assertThat(result.get().recommendedAction()).isEqualTo("HOLD_VESSEL");
    }

    @Test
    void returnsEmptyWhenTelemetryIsNominal() {
        Optional<CongestionAlertEvent> result = ruleEngine.evaluate(event(0.70, false, null));

        assertThat(result).isEmpty();
    }

    private ProcessedTelemetryEvent event(double fillRate, boolean blockageDetected, String vesselEta) {
        return new ProcessedTelemetryEvent(
                "OP-TEST-001",
                "B-12",
                fillRate,
                blockageDetected,
                "HD-07",
                vesselEta,
                "2026-05-31T18:00:00Z"
        );
    }
}
