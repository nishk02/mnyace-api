package com.mnyace.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.mnyace.shared.email.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

class ListSessionsTest extends AuthIntegrationTestBase {
    private static final String MEMBER_EMAIL = "listing-user@test.local";
    private static final String MEMBER_PASSWORD = "MemberPass123!";

    @MockitoBean
    private EmailService emailService;

    @Test
    void twoDevices_areListedAndTheCallerIsMarkedCurrent() {
        String phone = label("phone");
        String laptop = label("laptop");
        String phoneToken = accessTokenOf(signInAdminAs(phone));
        signInAdminAs(laptop);

        List<JsonNode> sessions = sessions(phoneToken);

        assertThat(withLabel(sessions, phone).path("current").asBoolean()).isTrue();
        assertThat(withLabel(sessions, laptop).path("current").asBoolean()).isFalse();
    }

    @Test
    void refresh_keepsOneEntryAndMovesLastActiveForward() {
        String label = label("tablet");
        HttpResponse<String> signedIn = signInAdminAs(label);
        Instant before = lastActive(withLabel(sessions(accessTokenOf(signedIn)), label));

        HttpResponse<String> refreshed = refresh(refreshTokenOf(signedIn), "X-Forwarded-For", freshIp());
        List<JsonNode> after = sessions(accessTokenOf(refreshed));

        assertThat(after.stream().filter(s -> label.equals(s.path("deviceLabel").asText()))).hasSize(1);
        assertThat(lastActive(withLabel(after, label))).isAfterOrEqualTo(before);
    }

    @Test
    void logout_removesThatDeviceFromTheOthersList() {
        String leaving = label("leaving");
        String staying = label("staying");
        HttpResponse<String> leavingDevice = signInAdminAs(leaving);
        String stayingToken = accessTokenOf(signInAdminAs(staying));

        post("/auth/logout", "{\"refreshToken\":\"" + refreshTokenOf(leavingDevice) + "\"}");

        List<JsonNode> sessions = sessions(stayingToken);
        assertThat(withLabel(sessions, leaving)).isNull();
        assertThat(withLabel(sessions, staying)).isNotNull();
    }

    @Test
    void signOutEverywhere_endsTheListRequestItself() {
        String token = accessTokenOf(signInAdminAs(label("only")));

        delete("/api/sessions", "Authorization", "Bearer " + token);

        assertThat(get("/api/sessions", "Authorization", "Bearer " + token).statusCode()).isEqualTo(401);
    }

    @Test
    void mostRecentlyActiveComesFirst() throws InterruptedException {
        String older = label("older");
        String newer = label("newer");
        HttpResponse<String> olderDevice = signInAdminAs(older);
        Thread.sleep(20);
        String newerToken = accessTokenOf(signInAdminAs(newer));

        assertThat(labels(sessions(newerToken)).indexOf(newer)).isLessThan(labels(sessions(newerToken)).indexOf(older));

        Thread.sleep(20);
        HttpResponse<String> refreshed = refresh(refreshTokenOf(olderDevice), "X-Forwarded-For", freshIp());

        List<String> labels = labels(sessions(accessTokenOf(refreshed)));
        assertThat(labels.indexOf(older)).isLessThan(labels.indexOf(newer));
    }

    @Test
    void anotherUsersSessions_neverAppear() {
        registerMember(MEMBER_EMAIL, null);
        String memberLabel = label("members-phone");
        String adminLabel = label("admins-phone");
        String memberToken = accessTokenOf(signInAs(MEMBER_EMAIL, MEMBER_PASSWORD, memberLabel));
        String adminToken = accessTokenOf(signInAdminAs(adminLabel));

        List<JsonNode> memberSessions = sessions(memberToken);
        List<JsonNode> adminSessions = sessions(adminToken);

        assertThat(withLabel(memberSessions, memberLabel)).isNotNull();
        assertThat(withLabel(memberSessions, adminLabel)).isNull();
        assertThat(withLabel(adminSessions, adminLabel)).isNotNull();
        assertThat(withLabel(adminSessions, memberLabel)).isNull();
    }

    @Test
    void signUpSession_isListedWithTheLabelSentAtVerifyOtp() {
        String label = label("signup-phone");

        String memberToken = accessTokenOf(registerMember("signup-" + UUID.randomUUID() + "@test.local", label));

        JsonNode session = withLabel(sessions(memberToken), label);
        assertThat(session).isNotNull();
        assertThat(session.path("current").asBoolean()).isTrue();
    }

    @Test
    void withoutAToken_isUnauthorized() {
        assertThat(get("/api/sessions").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> signInAdminAs(String label) {
        return signInAs(ADMIN_EMAIL, ADMIN_PASSWORD, label);
    }

    private HttpResponse<String> signInAs(String email, String password, String label) {
        return post("/auth/signin", "{\"email\":\"" + email + "\",\"password\":\"" + password
                + "\",\"deviceLabel\":\"" + label + "\"}", "X-Forwarded-For", freshIp());
    }

    private List<JsonNode> sessions(String accessToken) {
        HttpResponse<String> response = get("/api/sessions", "Authorization", "Bearer " + accessToken);
        assertThat(response.statusCode()).isEqualTo(200);
        List<JsonNode> sessions = new ArrayList<>();
        json(response).path("data").forEach(sessions::add);
        return sessions;
    }

    private static JsonNode withLabel(List<JsonNode> sessions, String label) {
        return sessions.stream().filter(s -> label.equals(s.path("deviceLabel").asText())).findFirst().orElse(null);
    }

    private static List<String> labels(List<JsonNode> sessions) {
        return sessions.stream().map(s -> s.path("deviceLabel").asText()).toList();
    }

    private static Instant lastActive(JsonNode session) {
        return Instant.parse(session.path("lastActiveAt").asText());
    }

    private static String label(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** Creates a second user through the invite flow. */
    private HttpResponse<String> registerMember(String email, String deviceLabel) {
        String adminAuth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        String inviteToken = json(post("/api/admin/invites", "{\"email\":\"" + email + "\"}",
                "Authorization", adminAuth)).path("data").path("token").asText();
        post("/auth/invites/" + inviteToken + "/signup",
                "{\"firstName\":\"Listing\",\"lastName\":\"User\",\"password\":\"" + MEMBER_PASSWORD
                        + "\",\"confirmPassword\":\"" + MEMBER_PASSWORD + "\"}", "X-Forwarded-For", freshIp());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendOtpEmail(eq(email), code.capture());
        String labelField = deviceLabel == null ? "" : ",\"deviceLabel\":\"" + deviceLabel + "\"";
        return post("/auth/invites/" + inviteToken + "/verify-otp",
                "{\"code\":\"" + code.getValue() + "\"" + labelField + "}", "X-Forwarded-For", freshIp());
    }
}
