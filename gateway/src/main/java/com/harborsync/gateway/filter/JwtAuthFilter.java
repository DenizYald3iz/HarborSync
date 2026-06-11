package com.harborsync.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    static final String BEARER_PREFIX = "Bearer ";
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final String jwtSecret;

    public JwtAuthFilter(@Value("${harborsync.jwt-secret}") String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (path.equals("/actuator/health") || path.startsWith("/actuator/")) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (!isValidBearerToken(authorization)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    boolean isValidBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return false;
        }
        return isValidJwt(authorization.substring(BEARER_PREFIX.length()).trim());
    }

    boolean isValidJwt(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }

        String signedContent = parts[0] + "." + parts[1];
        String expectedSignature = sign(signedContent);
        return MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8)
        );
    }

    private String sign(String content) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("JWT signature validation failed", ex);
        }
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
