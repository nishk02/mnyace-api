package com.mnyace.auth;

import com.mnyace.shared.security.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

// An access token is only good while the session it names is still live.
class RevokedSessionTokenTest extends AuthIntegrationTestBase {
    @Autowired
    private JwtUtil jwtUtil;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Test
    void liveSession_tokenIsAccepted() {
        HttpResponse<String> signedIn = signInAdmin("X-Forwarded-For", freshIp());

        assertThat(statusWith(accessTokenOf(signedIn))).isEqualTo(200);
    }

    @Test
    void logout_endsTheAccessTokenAtOnce() {
        HttpResponse<String> signedIn = signInAdmin("X-Forwarded-For", freshIp());
        String token = accessTokenOf(signedIn);

        post("/auth/logout", "{\"refreshToken\":\"" + refreshTokenOf(signedIn) + "\"}");

        assertThat(statusWith(token)).isEqualTo(401);
    }

    @Test
    void signOutEverywhere_endsEveryDevicesToken() {
        String phone = accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        String laptop = accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));

        delete("/api/sessions", "Authorization", "Bearer " + phone);

        assertThat(statusWith(phone)).isEqualTo(401);
        assertThat(statusWith(laptop)).isEqualTo(401);
    }

    @Test
    void passwordChange_endsTheOtherDevicesToken() {
        String temporary = "TempPass456!";
        String otherDevice = accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        HttpResponse<String> thisDevice = signInAdmin("X-Forwarded-For", freshIp());

        HttpResponse<String> changed = put("/api/users/me/password", passwordBody(ADMIN_PASSWORD, temporary),
                "Authorization", "Bearer " + accessTokenOf(thisDevice));

        try {
            assertThat(statusWith(otherDevice)).isEqualTo(401);
            assertThat(statusWith(accessTokenOf(changed))).isEqualTo(200);
        } finally {
            // always put the password back, so a failure here cannot break the other tests
            put("/api/users/me/password", passwordBody(temporary, ADMIN_PASSWORD),
                    "Authorization", "Bearer " + accessTokenOf(changed));
        }
    }

    @Test
    void refresh_keepsTheSessionAlive() {
        String ip = freshIp();
        HttpResponse<String> signedIn = signInAdmin("X-Forwarded-For", ip);
        HttpResponse<String> refreshed = refresh(refreshTokenOf(signedIn), "X-Forwarded-For", ip);

        assertThat(statusWith(accessTokenOf(refreshed))).isEqualTo(200);
        assertThat(statusWith(accessTokenOf(signedIn))).isEqualTo(200);
    }

    @Test
    void tokenForAnUnknownSession_isRejected() {
        String userId = json(signInAdmin("X-Forwarded-For", freshIp())).path("data").path("user").path("id").asText();
        String token = jwtUtil.generateToken(userId, ADMIN_EMAIL, "ADMIN", "no-such-session");

        assertThat(statusWith(token)).isEqualTo(401);
    }

    @Test
    void tokenWithoutASessionId_isRejected() {
        String userId = json(signInAdmin("X-Forwarded-For", freshIp())).path("data").path("user").path("id").asText();
        String token = Jwts.builder()
                .subject(userId)
                .claim("email", ADMIN_EMAIL)
                .claim("role", "ADMIN")
                .expiration(new Date(System.currentTimeMillis() + 300_000))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(statusWith(token)).isEqualTo(401);
    }

    private int statusWith(String accessToken) {
        return get("/api/accounts", "Authorization", "Bearer " + accessToken).statusCode();
    }

    private static String passwordBody(String current, String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next
                + "\",\"confirmNewPassword\":\"" + next + "\"}";
    }
}
