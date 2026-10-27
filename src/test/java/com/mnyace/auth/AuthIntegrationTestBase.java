package com.mnyace.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

// Real Tomcat + prod profile + a throwaway SQLite file per test class, so tests exercise
// the same filter chain, cookie handling and DB locking the deployed app does.
// The bootstrap admin is the account tests sign in with; rate limits are raised so only
// the tests that are about rate limiting ever see a 429.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
public abstract class AuthIntegrationTestBase {
    protected static final String ADMIN_EMAIL = "admin@test.local";
    protected static final String ADMIN_PASSWORD = "AdminPass123!";

    protected static final ObjectMapper JSON = new ObjectMapper();

    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @LocalServerPort
    protected int port;

    protected final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:target/it-" + UUID.randomUUID() + ".db");
        registry.add("jwt.secret", () -> "test-secret-test-secret-test-secret-123456");
        registry.add("admin.bootstrap.email", () -> ADMIN_EMAIL);
        registry.add("admin.bootstrap.password", () -> ADMIN_PASSWORD);
        registry.add("rate-limit.signin.ip.capacity", () -> "1000");
        registry.add("rate-limit.signin.email.capacity", () -> "1000");
        registry.add("rate-limit.refresh.ip.capacity", () -> "1000");
        registry.add("rate-limit.invite.ip.capacity", () -> "1000");
        registry.add("rate-limit.api.ip.capacity", () -> "100000");
    }

    /** A unique client IP per call, so tests never share an IP bucket. */
    protected static String freshIp() {
        return "10.9.0." + NEXT_IP.getAndIncrement();
    }

    protected HttpResponse<String> post(String path, String body, String... headers) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), headers);
    }

    protected HttpResponse<String> put(String path, String body, String... headers) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)), headers);
    }

    protected HttpResponse<String> delete(String path, String... headers) {
        return send(HttpRequest.newBuilder(uri(path)).DELETE(), headers);
    }

    protected HttpResponse<String> get(String path, String... headers) {
        return send(HttpRequest.newBuilder(uri(path)).GET(), headers);
    }

    protected HttpResponse<String> signIn(String email, String password, String... headers) {
        return post("/auth/signin", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", headers);
    }

    protected HttpResponse<String> signInAdmin(String... headers) {
        return signIn(ADMIN_EMAIL, ADMIN_PASSWORD, headers);
    }

    protected HttpResponse<String> refresh(String refreshToken, String... headers) {
        return post("/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}", headers);
    }

    protected static JsonNode json(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Not JSON: " + response.body(), e);
        }
    }

    protected static String refreshTokenOf(HttpResponse<String> response) {
        return json(response).path("data").path("refreshToken").asText();
    }

    protected static String accessTokenOf(HttpResponse<String> response) {
        return json(response).path("data").path("token").asText();
    }

    private URI uri(String rawPath) {
        return URI.create("http://localhost:" + port + rawPath);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, String... headers) {
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        try {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
