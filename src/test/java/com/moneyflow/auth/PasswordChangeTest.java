package com.moneyflow.auth;

import com.moneyflow.shared.email.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

class PasswordChangeTest extends AuthIntegrationTestBase {
    private static final String OLD = "OldPassword123!";
    private static final String NEW = "NewPassword456!";

    @MockitoBean
    private EmailService emailService;

    private record Member(String email, String access, String refresh) {
    }

    @Test
    void changesThePassword_andTheOldOneStopsWorking() {
        Member member = registerMember();

        HttpResponse<String> response = changePassword(member.access(), OLD, NEW, NEW);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(signIn(member.email(), OLD, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(401);
        assertThat(signIn(member.email(), NEW, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(200);
    }

    @Test
    void otherDevicesAreSignedOut_butTheCallerKeepsAFreshSession() {
        Member member = registerMember();
        String otherDevice = refreshTokenOf(signIn(member.email(), OLD, "X-Forwarded-For", freshIp()));

        HttpResponse<String> response = changePassword(member.access(), OLD, NEW, NEW);

        assertThat(refresh(member.refresh(), "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(401);
        assertThat(refresh(otherDevice, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(401);
        String newRefresh = refreshTokenOf(response);
        assertThat(newRefresh).isNotBlank();
        assertThat(refresh(newRefresh, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(200);
        assertThat(get("/api/accounts", "Authorization", "Bearer " + accessTokenOf(response)).statusCode())
                .isEqualTo(200);
    }

    @Test
    void aWrongCurrentPassword_isRejectedWithoutLoggingTheUserOut() {
        Member member = registerMember();

        HttpResponse<String> response = changePassword(member.access(), "not-the-password", NEW, NEW);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("error").path("code").asText()).isEqualTo("INVALID_CURRENT_PASSWORD");
        assertThat(signIn(member.email(), OLD, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(200);
        assertThat(refresh(member.refresh(), "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(200);
    }

    @Test
    void invalidNewPasswords_areRejected() {
        Member member = registerMember();

        assertThat(changePassword(member.access(), OLD, "short1!", "short1!").statusCode()).isEqualTo(400);
        assertThat(changePassword(member.access(), OLD, "a".repeat(73), "a".repeat(73)).statusCode()).isEqualTo(400);
        // 40 characters but 80 UTF-8 bytes: BCrypt only reads the first 72 bytes
        assertThat(changePassword(member.access(), OLD, "é".repeat(40), "é".repeat(40)).statusCode()).isEqualTo(400);
        assertThat(changePassword(member.access(), OLD, NEW, NEW + "x").statusCode()).isEqualTo(400);
        assertThat(changePassword(member.access(), OLD, OLD, OLD).statusCode()).isEqualTo(400);

        assertThat(signIn(member.email(), OLD, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(200);
    }

    @Test
    void requiresAValidAccessToken() {
        assertThat(put("/api/users/me/password", body(OLD, NEW, NEW)).statusCode()).isEqualTo(401);
    }

    @Test
    void currentPasswordGuesses_areLimitedPerUser() {
        Member member = registerMember();

        for (int i = 0; i < 5; i++) {
            assertThat(changePassword(member.access(), "wrong-" + i, NEW, NEW).statusCode()).isEqualTo(400);
        }

        assertThat(changePassword(member.access(), OLD, NEW, NEW).statusCode()).isEqualTo(429);
    }

    @Test
    void webClients_getTheNewRefreshTokenAsACookie() {
        Member member = registerMember();

        HttpResponse<String> response = changePassword(member.access(), OLD, NEW, NEW, "X-Client-Platform", "web");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("data").has("refreshToken")).isFalse();
        assertThat(response.headers().firstValue("Set-Cookie").orElseThrow())
                .startsWith("refreshToken=").contains("HttpOnly", "Secure", "SameSite=Strict");
    }

    @Test
    void doesNotAffectAnotherUsersSessions() {
        Member changer = registerMember();
        Member bystander = registerMember();

        changePassword(changer.access(), OLD, NEW, NEW);

        assertThat(refresh(bystander.refresh(), "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> changePassword(String access, String current, String next, String confirm,
                                                String... extraHeaders) {
        String[] headers = new String[extraHeaders.length + 2];
        headers[0] = "Authorization";
        headers[1] = "Bearer " + access;
        System.arraycopy(extraHeaders, 0, headers, 2, extraHeaders.length);
        return put("/api/users/me/password", body(current, next, confirm), headers);
    }

    private static String body(String current, String next, String confirm) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next
                + "\",\"confirmNewPassword\":\"" + confirm + "\"}";
    }

    /** Creates a member through the invite flow and returns their email and first session. */
    private Member registerMember() {
        String email = "member-" + UUID.randomUUID() + "@test.local";
        String adminAuth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        String inviteToken = json(post("/api/admin/invites", "{\"email\":\"" + email + "\"}",
                "Authorization", adminAuth)).path("data").path("token").asText();
        post("/auth/invites/" + inviteToken + "/signup",
                "{\"firstName\":\"Pat\",\"lastName\":\"Member\",\"password\":\"" + OLD
                        + "\",\"confirmPassword\":\"" + OLD + "\"}", "X-Forwarded-For", freshIp());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendOtpEmail(eq(email), code.capture());
        HttpResponse<String> verified = post("/auth/invites/" + inviteToken + "/verify-otp",
                "{\"code\":\"" + code.getValue() + "\"}", "X-Forwarded-For", freshIp());
        return new Member(email, accessTokenOf(verified), refreshTokenOf(verified));
    }
}
