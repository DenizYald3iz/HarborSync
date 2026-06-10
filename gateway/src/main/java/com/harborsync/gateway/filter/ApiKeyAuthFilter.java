package com.harborsync.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ApiKeyAuthFilter implements GlobalFilter, Ordered {

    static final String API_KEY_HEADER = "X-API-Key";

    private final String apiKey;

    public ApiKeyAuthFilter(@Value("${harborsync.api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (path.equals("/actuator/health") || path.startsWith("/actuator/")) {
            return chain.filter(exchange);
        }

        String requestKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);

        if (requestKey == null || !requestKey.equals(apiKey)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
