package com.harborsync.gateway.config;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver remoteAddressKeyResolver() {
        return exchange -> {
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            if (remoteAddress == null
                || remoteAddress.getHostString() == null
                || remoteAddress.getHostString().isBlank()) {
                return Mono.just("anonymous");
            }
            return Mono.just(remoteAddress.getHostString());
        };
    }
}
