package com.mnyace.auth;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class WebCookieAndLogoutTest extends AuthIntegrationTestBase {
    private static final String WEB = "web";

    @Test
    void webSignin_setsHardenedCookie_andKeepsTokenOutOfTheBody() {
        var response = signInAdmin("X-Forwarded-For", freshIp(), "X-Client-Platform", WEB);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("data").has("refreshToken")).isFalse();
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).startsWith("refreshToken=")
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/auth");
    }

    @Test
    void nativeSignin_returnsTokenInBody_andNoCookie() {
        var response = signInAdmin("X-Forwarded-For", freshIp());

        assertThat(refreshTokenOf(response)).isNotBlank();
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void platformHeader_isExactMatch_soOtherValuesGetTheBodyToken() {
        var response = signInAdmin("X-Forwarded-For", freshIp(), "X-Client-Platform", "WEB");

        assertThat(refreshTokenOf(response)).isNotBlank();
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void webRefresh_readsTheCookie_andRotatesIt() {
        String ip = freshIp();
        String first = cookieValue(signInAdmin("X-Forwarded-For", ip, "X-Client-Platform", WEB));

        var response = post("/auth/refresh", "{}", "X-Forwarded-For", ip, "X-Client-Platform", WEB,
                "Cookie", "refreshToken=" + first);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(cookieValue(response)).isNotEqualTo(first);
        assertThat(json(response).path("data").has("refreshToken")).isFalse();
    }

    @Test
    void webRefresh_ignoresATokenSentInTheBody() {
        String ip = freshIp();
        String bodyToken = refreshTokenOf(signInAdmin("X-Forwarded-For", ip));

        var response = post("/auth/refresh", "{\"refreshToken\":\"" + bodyToken + "\"}",
                "X-Forwarded-For", ip, "X-Client-Platform", WEB);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void nativeRefresh_ignoresTheCookie() {
        String ip = freshIp();
        String cookieToken = cookieValue(signInAdmin("X-Forwarded-For", ip, "X-Client-Platform", WEB));

        var response = post("/auth/refresh", "{}", "X-Forwarded-For", ip, "Cookie", "refreshToken=" + cookieToken);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void nativeLogout_revokesTheWholeFamily() {
        String ip = freshIp();
        String first = refreshTokenOf(signInAdmin("X-Forwarded-For", ip));
        String second = refreshTokenOf(refresh(first, "X-Forwarded-For", ip));

        var logout = post("/auth/logout", "{\"refreshToken\":\"" + second + "\"}", "X-Forwarded-For", ip);

        assertThat(logout.statusCode()).isEqualTo(200);
        assertThat(refresh(second, "X-Forwarded-For", ip).statusCode()).isEqualTo(401);
    }

    @Test
    void logout_onlyEndsThatDevice() {
        String ip = freshIp();
        String deviceA = refreshTokenOf(signInAdmin("X-Forwarded-For", ip));
        String deviceB = refreshTokenOf(signInAdmin("X-Forwarded-For", ip));

        post("/auth/logout", "{\"refreshToken\":\"" + deviceA + "\"}", "X-Forwarded-For", ip);

        assertThat(refresh(deviceA, "X-Forwarded-For", ip).statusCode()).isEqualTo(401);
        assertThat(refresh(deviceB, "X-Forwarded-For", ip).statusCode()).isEqualTo(200);
    }

    @Test
    void webLogout_clearsTheCookie() {
        String ip = freshIp();
        String token = cookieValue(signInAdmin("X-Forwarded-For", ip, "X-Client-Platform", WEB));

        var logout = post("/auth/logout", "{}", "X-Forwarded-For", ip, "X-Client-Platform", WEB,
                "Cookie", "refreshToken=" + token);

        assertThat(logout.statusCode()).isEqualTo(200);
        assertThat(logout.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=0");
    }

    private static String cookieValue(HttpResponse<String> response) {
        String header = response.headers().firstValue("Set-Cookie").orElseThrow();
        return header.substring("refreshToken=".length(), header.indexOf(';'));
    }
}
