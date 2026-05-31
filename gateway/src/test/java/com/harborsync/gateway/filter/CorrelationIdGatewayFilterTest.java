package com.harborsync.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class CorrelationIdGatewayFilterTest {

    private final CorrelationIdGatewayFilter filter = new CorrelationIdGatewayFilter();

    @Test
    void createsCorrelationIdWhenHeaderIsMissingAndAddsItToRequestAndResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/tasks").build()
        );
        AtomicReference<String> downstreamCorrelationId = new AtomicReference<>();
        GatewayFilterChain chain = filteredExchange -> {
            downstreamCorrelationId.set(filteredExchange.getRequest().getHeaders()
                .getFirst(CorrelationIdGatewayFilter.CORRELATION_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamCorrelationId.get()).isNotBlank();
        assertThat(exchange.getResponse().getHeaders()
            .getFirst(CorrelationIdGatewayFilter.CORRELATION_HEADER))
            .isEqualTo(downstreamCorrelationId.get());
    }

    @Test
    void preservesIncomingCorrelationIdForDownstreamRequestAndResponse() {
        String correlationId = "OP-2025-001";
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/vessels")
                .header(CorrelationIdGatewayFilter.CORRELATION_HEADER, correlationId)
                .build()
        );
        AtomicReference<ServerHttpRequest> downstreamRequest = new AtomicReference<>();
        GatewayFilterChain chain = filteredExchange -> {
            downstreamRequest.set(filteredExchange.getRequest());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamRequest.get().getHeaders()
            .getFirst(CorrelationIdGatewayFilter.CORRELATION_HEADER))
            .isEqualTo(correlationId);
        assertThat(exchange.getResponse().getHeaders()
            .getFirst(CorrelationIdGatewayFilter.CORRELATION_HEADER))
            .isEqualTo(correlationId);
    }
}
