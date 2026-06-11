package com.harborsync.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class JwtAuthFilterTest {

    private static final String SECRET = "test-jwt-secret";

    private final JwtAuthFilter filter = new JwtAuthFilter(SECRET);

    @Test
    void rejectsRequestWhenAuthorizationHeaderIsMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/vessels").build()
        );

        filter.filter(exchange, chain -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsRequestWhenJwtSignatureIsInvalid() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/vessels")
                .header(HttpHeaders.AUTHORIZATION, JwtAuthFilter.BEARER_PREFIX + token("wrong-secret"))
                .build()
        );

        filter.filter(exchange, chain -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void allowsRequestWhenJwtSignatureIsValid() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/vessels")
                .header(HttpHeaders.AUTHORIZATION, JwtAuthFilter.BEARER_PREFIX + token(SECRET))
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
    void allowsHealthEndpointWithoutJwt() {
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

    private String token(String secret) {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"operator\",\"scope\":\"harborsync\"}");
        String signedContent = header + "." + payload;
        return signedContent + "." + sign(secret, signedContent);
    }

    private String sign(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
