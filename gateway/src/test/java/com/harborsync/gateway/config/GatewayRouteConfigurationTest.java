package com.harborsync.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class GatewayRouteConfigurationTest {

    @Test
    void applicationYamlContainsExpectedRoutesAndRateLimits() throws Exception {
        ClassPathResource resource = new ClassPathResource("application.yml");
        String yaml = resource.getContentAsString(StandardCharsets.UTF_8);

        assertThat(yaml).contains("Path=/api/vessels/**");
        assertThat(yaml).contains("Path=/api/tasks/**");
        assertThat(yaml).contains("StripPrefix=1");
        assertThat(yaml).contains("redis-rate-limiter.replenishRate: 10");
        assertThat(yaml).contains("redis-rate-limiter.burstCapacity: 20");
    }
}
