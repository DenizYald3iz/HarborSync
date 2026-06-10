package com.harborsync.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;

class ApiKeyAuthFilterTest {

    private static final String VALID_KEY = "test-key-123";

    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(VALID_KEY);

    @Test
    void rejectsRequestWhenApiKeyHeaderIsMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/vessels").build()
        );

        filter.filter(exchange, chain -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsRequestWhenApiKeyHeaderIsInvalid() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/vessels")
                .header(ApiKeyAuthFilter.API_KEY_HEADER, "wrong-key")
                .build()
        );

        filter.filter(exchange, chain -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void allowsRequestWhenApiKeyHeaderIsValid() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/vessels")
                .header(ApiKeyAuthFilter.API_KEY_HEADER, VALID_KEY)
                .build()
        );
        boolean[] chainCalled = {false};

        filter.filter(exchange, filteredExchange -> {
            chainCalled[0] = true;
            return Mono.empty();
        }).block();

        assertThat(chainCalled[0]).isTrue();
    }

    @Test
    void allowsHealthEndpointWithoutApiKey() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/actuator/health").build()
        );
        boolean[] chainCalled = {false};

        filter.filter(exchange, filteredExchange -> {
            chainCalled[0] = true;
            return Mono.empty();
        }).block();

        assertThat(chainCalled[0]).isTrue();
    }
}
