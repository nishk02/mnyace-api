package com.moneyflow.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Transport-only concern: knows about X-Client-Platform and the refreshToken
// cookie, so AuthService/InviteService/RefreshToken* never have to.
@Component
public class RefreshTokenCookieWriter {
    private static final String COOKIE_NAME = "refreshToken";
    private static final String COOKIE_PATH = "/auth";
    private static final String PLATFORM_WEB = "web";

    @Value("${refresh-token.expiration-days}")
    private long expirationDays;

    public boolean isWeb(String platformHeader) {
        return PLATFORM_WEB.equals(platformHeader);
    }

    // Web reads the token from the cookie; everyone else reads it from the body.
    public String resolveIncomingToken(String platformHeader, String cookieValue, String bodyValue) {
        return isWeb(platformHeader) ? cookieValue : bodyValue;
    }

    public String buildSetCookieHeader(String rawToken) {
        return ResponseCookie.from(COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(Duration.ofDays(expirationDays))
                .build()
                .toString();
    }

    public String buildClearCookieHeader() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(0)
                .build()
                .toString();
    }
}