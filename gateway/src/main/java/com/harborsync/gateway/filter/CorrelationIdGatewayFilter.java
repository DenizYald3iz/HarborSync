package com.harborsync.gateway.filter;

import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class CorrelationIdGatewayFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_HEADER = "X-Correlation-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_HEADER);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        String finalCorrelationId = correlationId;
        ServerHttpRequest request = exchange.getRequest()
            .mutate()
            .headers(headers -> headers.set(CORRELATION_HEADER, finalCorrelationId))
            .build();

        ServerWebExchange mutatedExchange = exchange.mutate().request(request).build();
        mutatedExchange.getResponse().getHeaders().set(CORRELATION_HEADER, finalCorrelationId);

        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
