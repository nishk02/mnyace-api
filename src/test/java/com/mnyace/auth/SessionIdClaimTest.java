package com.mnyace.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

// The access token names the session it belongs to, so a request can tell which device it is.
class SessionIdClaimTest extends AuthIntegrationTestBase {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void signIn_putsTheSessionIdInTheAccessToken() {
        String sessionId = sessionIdOf(signInAdmin("X-Forwarded-For", freshIp()));

        assertThat(sessionId).isNotBlank();
    }

    @Test
    void twoSignIns_areTwoSessions() {
        String first = sessionIdOf(signInAdmin("X-Forwarded-For", freshIp()));
        String second = sessionIdOf(signInAdmin("X-Forwarded-For", freshIp()));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void refresh_keepsTheSameSessionId() {
        String ip = freshIp();
        HttpResponse<String> signedIn = signInAdmin("X-Forwarded-For", ip);
        HttpResponse<String> refreshed = refresh(refreshTokenOf(signedIn), "X-Forwarded-For", ip);

        assertThat(sessionIdOf(signedIn)).isNotBlank();
        assertThat(sessionIdOf(refreshed)).isEqualTo(sessionIdOf(signedIn));
    }

    @Test
    void changingThePassword_startsANewSession() {
        String ip = freshIp();
        HttpResponse<String> signedIn = signInAdmin("X-Forwarded-For", ip);
        String temporary = "TempPass456!";

        HttpResponse<String> changed = put("/api/users/me/password", passwordBody(ADMIN_PASSWORD, temporary),
                "Authorization", "Bearer " + accessTokenOf(signedIn), "X-Forwarded-For", ip);
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(sessionIdOf(changed)).isNotEqualTo(sessionIdOf(signedIn));

        // put the password back so the other tests in this class can still sign in
        HttpResponse<String> restored = put("/api/users/me/password", passwordBody(temporary, ADMIN_PASSWORD),
                "Authorization", "Bearer " + accessTokenOf(changed), "X-Forwarded-For", ip);
        assertThat(restored.statusCode()).isEqualTo(200);
    }

    private static String passwordBody(String current, String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next
                + "\",\"confirmNewPassword\":\"" + next + "\"}";
    }

    private static String sessionIdOf(HttpResponse<String> response) {
        try {
            String payload = accessTokenOf(response).split("\\.")[1];
            String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            return MAPPER.readTree(json).path("sid").asText("");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
