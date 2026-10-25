package com.moneyflow.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SigninTest extends AuthIntegrationTestBase {

    @Test
    void validCredentials_returnAccessAndRefreshToken() {
        var response = signInAdmin("X-Forwarded-For", freshIp());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(accessTokenOf(response)).isNotBlank();
        assertThat(refreshTokenOf(response)).isNotBlank();
        assertThat(json(response).path("data").path("user").path("email").asText()).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    void wrongPassword_andUnknownEmail_areIndistinguishable() {
        var wrongPassword = signIn(ADMIN_EMAIL, "not-the-password", "X-Forwarded-For", freshIp());
        var unknownEmail = signIn("nobody@test.local", "not-the-password", "X-Forwarded-For", freshIp());

        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(unknownEmail.statusCode()).isEqualTo(401);
        assertThat(json(wrongPassword).path("error")).isEqualTo(json(unknownEmail).path("error"));
    }

    @Test
    void malformedBody_isRejectedWith400() {
        assertThat(post("/auth/signin", "{not json", "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(400);
        assertThat(post("/auth/signin", "{\"email\":\"not-an-email\",\"password\":\"x\"}",
                "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(400);
    }

    @Test
    void protectedEndpoint_requiresAValidAccessToken() {
        assertThat(get("/api/accounts").statusCode()).isEqualTo(401);
        assertThat(get("/api/accounts", "Authorization", "Bearer garbage").statusCode()).isEqualTo(401);

        String token = accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        assertThat(get("/api/accounts", "Authorization", "Bearer " + token).statusCode()).isEqualTo(200);
    }

    @Test
    void tamperedAccessToken_isRejected() {
        String token = accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertThat(get("/api/accounts", "Authorization", "Bearer " + tampered).statusCode()).isEqualTo(401);
    }
}
