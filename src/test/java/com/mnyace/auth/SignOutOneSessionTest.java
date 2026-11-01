package com.mnyace.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.mnyace.shared.email.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

class SignOutOneSessionTest extends AuthIntegrationTestBase {
    private static final String MEMBER_EMAIL = "one-session-user@test.local";
    private static final String MEMBER_PASSWORD = "MemberPass123!";

    @MockitoBean
    private EmailService emailService;

    @Test
    void signingOutAnotherDevice_endsItAtOnce_andLeavesTheCallerSignedIn() {
        String laptopLabel = label("laptop");
        String phoneToken = accessTokenOf(signInAdminAs(label("phone")));
        HttpResponse<String> laptop = signInAdminAs(laptopLabel);
        String laptopId = sessionIdLabelled(phoneToken, laptopLabel);

        HttpResponse<String> response = delete("/api/sessions/" + laptopId, "Authorization", "Bearer " + phoneToken);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(apiStatus(accessTokenOf(laptop))).isEqualTo(401);
        assertThat(refresh(refreshTokenOf(laptop), "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(401);
        assertThat(apiStatus(phoneToken)).isEqualTo(200);
        assertThat(sessionIdLabelled(phoneToken, laptopLabel)).isNull();
    }

    @Test
    void theCallersOwnSession_isRefused() {
        String label = label("this-device");
        String token = accessTokenOf(signInAdminAs(label));
        String ownId = sessionIdLabelled(token, label);

        HttpResponse<String> response = delete("/api/sessions/" + ownId, "Authorization", "Bearer " + token);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).at("/error/code").asText()).isEqualTo("CURRENT_SESSION");
        assertThat(apiStatus(token)).isEqualTo(200);
    }

    @Test
    void anUnknownSession_isNotFound() {
        String token = accessTokenOf(signInAdminAs(label("caller")));

        assertThat(delete("/api/sessions/no-such-session", "Authorization", "Bearer " + token).statusCode())
                .isEqualTo(404);
    }

    @Test
    void anotherUsersSession_isNotFound_andLeftAlone() {
        registerMember();
        String memberLabel = label("members-phone");
        String memberToken = accessTokenOf(signInAs(MEMBER_EMAIL, MEMBER_PASSWORD, memberLabel));
        String membersSessionId = sessionIdLabelled(memberToken, memberLabel);
        String adminToken = accessTokenOf(signInAdminAs(label("admin")));

        HttpResponse<String> response = delete("/api/sessions/" + membersSessionId, "Authorization", "Bearer " + adminToken);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(apiStatus(memberToken)).isEqualTo(200);
    }

    @Test
    void signingOutTheSameSessionTwice_secondTimeIsNotFound() {
        String laptopLabel = label("laptop");
        String phoneToken = accessTokenOf(signInAdminAs(label("phone")));
        signInAdminAs(laptopLabel);
        String laptopId = sessionIdLabelled(phoneToken, laptopLabel);

        assertThat(delete("/api/sessions/" + laptopId, "Authorization", "Bearer " + phoneToken).statusCode()).isEqualTo(200);
        assertThat(delete("/api/sessions/" + laptopId, "Authorization", "Bearer " + phoneToken).statusCode()).isEqualTo(404);
    }

    @Test
    void withoutAToken_isUnauthorized() {
        assertThat(delete("/api/sessions/anything").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> signInAdminAs(String label) {
        return signInAs(ADMIN_EMAIL, ADMIN_PASSWORD, label);
    }

    private HttpResponse<String> signInAs(String email, String password, String label) {
        return post("/auth/signin", "{\"email\":\"" + email + "\",\"password\":\"" + password
                + "\",\"deviceLabel\":\"" + label + "\"}", "X-Forwarded-For", freshIp());
    }

    private int apiStatus(String accessToken) {
        return get("/api/accounts", "Authorization", "Bearer " + accessToken).statusCode();
    }

    /** The id of the caller's session with this label, or null when it is not listed. */
    private String sessionIdLabelled(String accessToken, String label) {
        for (JsonNode session : json(get("/api/sessions", "Authorization", "Bearer " + accessToken)).path("data")) {
            if (label.equals(session.path("deviceLabel").asText())) {
                return session.path("id").asText();
            }
        }
        return null;
    }

    private static String label(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** Creates a second user through the invite flow. */
    private void registerMember() {
        String adminAuth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        String inviteToken = json(post("/api/admin/invites", "{\"email\":\"" + MEMBER_EMAIL + "\"}",
                "Authorization", adminAuth)).path("data").path("token").asText();
        post("/auth/invites/" + inviteToken + "/signup",
                "{\"firstName\":\"One\",\"lastName\":\"Session\",\"password\":\"" + MEMBER_PASSWORD
                        + "\",\"confirmPassword\":\"" + MEMBER_PASSWORD + "\"}", "X-Forwarded-For", freshIp());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendOtpEmail(eq(MEMBER_EMAIL), code.capture());
        post("/auth/invites/" + inviteToken + "/verify-otp",
                "{\"code\":\"" + code.getValue() + "\"}", "X-Forwarded-For", freshIp());
    }
}
