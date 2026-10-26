package com.moneyflow.auth;

import com.moneyflow.shared.email.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

class SignOutEverywhereTest extends AuthIntegrationTestBase {
    private static final String MEMBER_EMAIL = "second-user@test.local";
    private static final String MEMBER_PASSWORD = "MemberPass123!";

    @MockitoBean
    private EmailService emailService;

    @Test
    void revokesTheRefreshTokensOfEveryDevice() {
        String ip = freshIp();
        HttpResponse<String> phone = signInAdmin("X-Forwarded-For", ip);
        String laptopRefresh = refreshTokenOf(signInAdmin("X-Forwarded-For", ip));
        String phoneRefresh = refreshTokenOf(phone);

        HttpResponse<String> response = signOutEverywhere("Bearer " + accessTokenOf(phone));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(refresh(phoneRefresh, "X-Forwarded-For", ip).statusCode()).isEqualTo(401);
        assertThat(refresh(laptopRefresh, "X-Forwarded-For", ip).statusCode()).isEqualTo(401);
    }

    @Test
    void doesNotTouchAnotherUsersSessions() {
        String memberRefresh = registerMember();
        HttpResponse<String> admin = signInAdmin("X-Forwarded-For", freshIp());

        signOutEverywhere("Bearer " + accessTokenOf(admin));

        assertThat(refresh(memberRefresh, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(200);
    }

    @Test
    void requiresAValidAccessToken() {
        assertThat(delete("/api/sessions").statusCode()).isEqualTo(401);
        assertThat(delete("/api/sessions", "Authorization", "Bearer garbage").statusCode()).isEqualTo(401);
    }

    @Test
    void webClients_getTheirCookieCleared() {
        HttpResponse<String> signedIn = signInAdmin("X-Forwarded-For", freshIp(), "X-Client-Platform", "web");

        HttpResponse<String> response = delete("/api/sessions",
                "Authorization", "Bearer " + accessTokenOf(signedIn), "X-Client-Platform", "web");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=0");
    }

    @Test
    void anAlreadyIssuedAccessToken_keepsWorkingUntilItExpires() {
        String access = accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));

        signOutEverywhere("Bearer " + access);

        assertThat(get("/api/accounts", "Authorization", "Bearer " + access).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> signOutEverywhere(String authorization) {
        return delete("/api/sessions", "Authorization", authorization);
    }

    /** Creates a second user through the invite flow and returns their refresh token. */
    private String registerMember() {
        String adminAuth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        String inviteToken = json(post("/api/admin/invites", "{\"email\":\"" + MEMBER_EMAIL + "\"}",
                "Authorization", adminAuth)).path("data").path("token").asText();
        post("/auth/invites/" + inviteToken + "/signup",
                "{\"firstName\":\"Second\",\"lastName\":\"User\",\"password\":\"" + MEMBER_PASSWORD
                        + "\",\"confirmPassword\":\"" + MEMBER_PASSWORD + "\"}", "X-Forwarded-For", freshIp());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendOtpEmail(eq(MEMBER_EMAIL), code.capture());
        return refreshTokenOf(post("/auth/invites/" + inviteToken + "/verify-otp",
                "{\"code\":\"" + code.getValue() + "\"}", "X-Forwarded-For", freshIp()));
    }
}
