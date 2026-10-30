package com.mnyace.shared.security;

import com.mnyace.auth.AuthIntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

// A browser only lets the web client read response headers the server lists as exposed.
class CorsExposedHeadersTest extends AuthIntegrationTestBase {
    private static final String WEB_ORIGIN = "https://app.mnyace.com";

    @Test
    void crossOriginResponse_exposesRetryAfterAndRequestId() {
        HttpResponse<String> response = post("/auth/signin",
                "{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}",
                "Origin", WEB_ORIGIN, "X-Forwarded-For", freshIp());

        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(WEB_ORIGIN);
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers").orElse(""))
                .contains("Retry-After")
                .contains("X-Request-Id");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
    }
}
