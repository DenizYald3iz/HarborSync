package com.harborsync.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class RateLimiterConfigTest {

    private final RateLimiterConfig config = new RateLimiterConfig();

    @Test
    void remoteAddressKeyResolverFallsBackToAnonymousWithoutRemoteAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/tasks").build()
        );

        String key = config.remoteAddressKeyResolver().resolve(exchange).block();

        assertThat(key).isEqualTo("anonymous");
    }

    @Test
    void remoteAddressKeyResolverUsesClientHostWhenPresent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/vessels")
                .remoteAddress(new InetSocketAddress("192.0.2.10", 49152))
                .build()
        );

        String key = config.remoteAddressKeyResolver().resolve(exchange).block();

        assertThat(key).isEqualTo("192.0.2.10");
    }
}
