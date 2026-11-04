package com.mnyace.shared.health;

import com.mnyace.auth.AuthIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

// a small health bucket with a long period, so a slow runner cannot earn tokens back mid-test
@TestPropertySource(properties = {
        "rate-limit.health.ip.capacity=3",
        "rate-limit.health.ip.period-seconds=86400"
})
class HealthEndpointTest extends AuthIntegrationTestBase {
    @Test
    void isPublic_andAnswersUp_withNoStore() {
        HttpResponse<String> response = get("/api/health", "X-Forwarded-For", freshIp());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).toString()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void aStaleOrGarbageToken_doesNotMakeItFail() {
        HttpResponse<String> response = get("/api/health", "X-Forwarded-For", freshIp(),
                "Authorization", "Bearer not.a.real.token");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void sendsCorsHeadersToAnAllowedOrigin() {
        HttpResponse<String> response = get("/api/health", "X-Forwarded-For", freshIp(),
                "Origin", "capacitor://localhost");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("capacitor://localhost");
    }

    @Test
    void otherEndpointsStayProtected() {
        assertThat(get("/api/accounts", "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(401);
        // only GET is public
        assertThat(post("/api/health", "{}", "X-Forwarded-For", freshIp()).statusCode()).isNotEqualTo(200);
    }

    @Test
    void hasItsOwnRateLimit_thatDoesNotTouchTheSharedApiBucket() {
        String ip = freshIp();
        for (int i = 0; i < 3; i++) {
            assertThat(get("/api/health", "X-Forwarded-For", ip).statusCode()).isEqualTo(200);
        }

        HttpResponse<String> limited = get("/api/health", "X-Forwarded-For", ip);

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        // the same address can still make ordinary API calls: the shared bucket was never charged
        assertThat(get("/api/accounts", "X-Forwarded-For", ip).statusCode()).isEqualTo(401);
    }
}
