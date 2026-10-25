package com.moneyflow.shared.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

// Real Tomcat (RANDOM_PORT), not MockMvc: the bypasses this guards against depend on how
// Tomcat decodes the request path before the servlet filters see it. java.net.http keeps
// the raw URI as given, whereas TestRestTemplate would re-encode '%' and hide the bug.
//
// Each test uses its own X-Forwarded-For value so the shared in-memory buckets
// never leak state between tests.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:target/rate-limit-it.db",
        "jwt.secret=test-secret-test-secret-test-secret-123456"
})
class RateLimitFilterIntegrationTest {
    @LocalServerPort
    private int port;

    @Value("${rate-limit.signin.ip.capacity}")
    private int signinIpCapacity;

    @Value("${rate-limit.refresh.ip.capacity}")
    private int refreshIpCapacity;

    @Value("${rate-limit.invite.ip.capacity}")
    private int inviteIpCapacity;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void signin_plainPath_isRateLimitedPerIp() throws Exception {
        assertLimitedAfter(signinIpCapacity, "/auth/signin", "10.1.0.1", i -> signinBody(i));
    }

    @Test
    void signin_percentEncodedPath_isRateLimitedPerIp() throws Exception {
        assertLimitedAfter(signinIpCapacity, "/auth/%73ignin", "10.1.0.2", i -> signinBody(i));
    }

    @Test
    void refresh_plainPath_isRateLimitedPerIp() throws Exception {
        assertLimitedAfter(refreshIpCapacity, "/auth/refresh", "10.1.0.3", i -> "{\"refreshToken\":\"x\"}");
    }

    @Test
    void refresh_percentEncodedPath_isRateLimitedPerIp() throws Exception {
        assertLimitedAfter(refreshIpCapacity, "/auth/%72efresh", "10.1.0.4", i -> "{\"refreshToken\":\"x\"}");
    }

    @Test
    void inviteVerifyOtp_plainPath_isRateLimitedPerIp() throws Exception {
        assertLimitedAfter(inviteIpCapacity, "/auth/invites/abc/verify-otp", "10.1.0.7", i -> "{\"code\":\"000000\"}");
    }

    @Test
    void inviteVerifyOtp_percentEncodedPath_isRateLimitedPerIp() throws Exception {
        assertLimitedAfter(inviteIpCapacity, "/auth/invites/abc/%76erify-otp", "10.1.0.8", i -> "{\"code\":\"000000\"}");
    }

    @Test
    void signin_oversizedBody_isRejectedWith413() throws Exception {
        String huge = "{\"email\":\"a@x.com\",\"password\":\"" + "p".repeat(1_000_000) + "\"}";

        HttpResponse<String> response = post("/auth/signin", "10.1.0.5", huge);

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void signin_oversizedBodies_stillConsumeTheIpBucket() throws Exception {
        String huge = "{\"email\":\"a@x.com\",\"password\":\"" + "p".repeat(100_000) + "\"}";

        for (int i = 0; i < signinIpCapacity; i++) {
            assertThat(post("/auth/signin", "10.1.0.6", huge).statusCode()).isEqualTo(413);
        }
        assertThat(post("/auth/signin", "10.1.0.6", huge).statusCode()).isEqualTo(429);
    }

    private void assertLimitedAfter(
            int capacity, String path, String ip, java.util.function.IntFunction<String> body
    ) throws Exception {
        for (int i = 0; i < capacity; i++) {
            assertThat(post(path, ip, body.apply(i)).statusCode())
                    .as("request %d of %d on %s should not be rate limited", i + 1, capacity, path)
                    .isNotEqualTo(429);
        }
        assertThat(post(path, ip, body.apply(capacity)).statusCode())
                .as("request %d on %s should be rate limited", capacity + 1, path)
                .isEqualTo(429);
    }

    // Distinct email per request so only the IP bucket can trip, never the email bucket.
    private String signinBody(int i) {
        return "{\"email\":\"user" + i + "@x.com\",\"password\":\"wrong\"}";
    }

    private HttpResponse<String> post(String rawPath, String forwardedFor, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
