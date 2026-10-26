package com.moneyflow.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.moneyflow.shared.email.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

class SecurityEventsTest extends AuthIntegrationTestBase {
    private static final String MEMBER_PASSWORD = "MemberPass123!";

    @MockitoBean
    private EmailService emailService;

    private ListAppender<ILoggingEvent> captured;
    private Logger securityLogger;

    @BeforeEach
    void captureSecurityLog() {
        securityLogger = (Logger) LoggerFactory.getLogger("SECURITY_EVENTS");
        captured = new ListAppender<>();
        captured.start();
        securityLogger.addAppender(captured);
    }

    @AfterEach
    void stopCapturing() {
        securityLogger.detachAppender(captured);
    }

    @Test
    void failedSignin_isLoggedWithTheReasonAndIp_butNeverTheEmailOrPassword() {
        String ip = freshIp();

        signIn("nobody@test.local", "SomePassword1!", "X-Forwarded-For", ip);
        signIn(ADMIN_EMAIL, "WrongPassword1!", "X-Forwarded-For", ip);

        JsonNode unknown = only("signin", "failure", "unknown_user");
        JsonNode wrong = only("signin", "failure", "wrong_password");
        assertThat(unknown.at("/source/ip").asText()).isEqualTo(ip);
        assertThat(unknown.at("/event/category").asText()).isEqualTo("authentication");
        assertThat(unknown.at("/user").isMissingNode()).isTrue();
        assertThat(wrong.at("/user/id").asText()).isNotBlank();
        assertNothingSecretIsLogged("nobody@test.local", "SomePassword1!", "WrongPassword1!");
    }

    @Test
    void successfulSignin_isLoggedWithTheUserId() {
        String ip = freshIp();

        HttpResponse<String> response = signInAdmin("X-Forwarded-For", ip);

        JsonNode event = last("signin", "success");
        assertThat(event.at("/user/id").asText())
                .isEqualTo(json(response).path("data").path("user").path("id").asText());
        assertThat(event.at("/source/ip").asText()).isEqualTo(ip);
        assertNothingSecretIsLogged(ADMIN_PASSWORD, refreshTokenOf(response), accessTokenOf(response));
    }

    @Test
    void everyEvent_isOneParsableJsonLineWithATimestamp() {
        signIn("nobody@test.local", "x", "X-Forwarded-For", freshIp());

        for (String line : lines()) {
            JsonNode event = parse(line);
            assertThat(event.path("@timestamp").asText()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
            assertThat(event.at("/event/category").asText()).isNotBlank();
            assertThat(event.at("/event/action").asText()).isNotBlank();
            assertThat(event.at("/event/outcome").asText()).isIn("success", "failure");
            assertThat(event.at("/service/name").asText()).isEqualTo("moneyflow-api");
            assertThat(line).doesNotContain("\n");
        }
    }

    @Test
    void passwordChange_andItsFailure_areLogged() {
        Member member = registerMember();

        changePassword(member.access(), "not-the-password", "NewPassword456!");
        changePassword(member.access(), MEMBER_PASSWORD, "NewPassword456!");

        assertThat(only("password_change", "failure", "wrong_current_password").at("/user/id").asText())
                .isEqualTo(member.userId());
        assertThat(last("password_change", "success").at("/user/id").asText()).isEqualTo(member.userId());
        assertNothingSecretIsLogged("not-the-password", MEMBER_PASSWORD, "NewPassword456!");
    }

    @Test
    void signOutEverywhere_isLogged() {
        HttpResponse<String> signedIn = signInAdmin("X-Forwarded-For", freshIp());

        delete("/api/sessions", "Authorization", "Bearer " + accessTokenOf(signedIn));

        assertThat(last("signout_all", "success").at("/user/id").asText())
                .isEqualTo(json(signedIn).path("data").path("user").path("id").asText());
    }

    @Test
    void refreshTokenReuse_isLoggedWithTheUserId() throws Exception {
        String ip = freshIp();
        HttpResponse<String> signedIn = signInAdmin("X-Forwarded-For", ip);
        String first = refreshTokenOf(signedIn);
        refresh(first, "X-Forwarded-For", ip);
        Thread.sleep(5_500); // past the duplicate-request window, so the replay counts as reuse

        refresh(first, "X-Forwarded-For", ip);

        JsonNode event = last("refresh_token_reuse", "failure");
        assertThat(event.at("/user/id").asText())
                .isEqualTo(json(signedIn).path("data").path("user").path("id").asText());
        assertThat(event.at("/event/category").asText()).isEqualTo("intrusion_detection");
        assertThat(event.at("/labels/session_family").asText()).isNotBlank();
    }

    @Test
    void inviteCreation_isLoggedWithoutTheInvitedEmail() {
        String invited = "invited-" + UUID.randomUUID() + "@test.local";
        String adminAuth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));

        post("/api/admin/invites", "{\"email\":\"" + invited + "\"}", "Authorization", adminAuth);

        JsonNode event = last("invite_create", "success");
        assertThat(event.at("/user/id").asText()).isNotBlank();
        assertThat(event.at("/labels/invite_id").asText()).isNotBlank();
        assertNothingSecretIsLogged(invited);
    }

    @Test
    void anOtpLockout_isLogged() {
        String email = "lockout-" + UUID.randomUUID() + "@test.local";
        String adminAuth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        String inviteToken = json(post("/api/admin/invites", "{\"email\":\"" + email + "\"}",
                "Authorization", adminAuth)).path("data").path("token").asText();
        post("/auth/invites/" + inviteToken + "/signup",
                "{\"firstName\":\"Pat\",\"lastName\":\"Member\",\"password\":\"" + MEMBER_PASSWORD
                        + "\",\"confirmPassword\":\"" + MEMBER_PASSWORD + "\"}", "X-Forwarded-For", freshIp());

        for (int i = 0; i < 11; i++) {
            post("/auth/invites/" + inviteToken + "/verify-otp", "{\"code\":\"000000\"}",
                    "X-Forwarded-For", freshIp());
        }

        assertThat(last("otp_lockout", "failure").at("/labels/invite_id").asText()).isNotBlank();
        assertNothingSecretIsLogged(inviteToken, "000000");
    }

    @Test
    void aRateLimitBlock_isLoggedWithTheBucketName() {
        String ip = freshIp();
        // the base class raises the limits, so use the per-user password bucket (5 attempts)
        Member member = registerMember();
        for (int i = 0; i < 6; i++) {
            changePassword(member.access(), "wrong-" + i, "NewPassword456!");
        }

        JsonNode event = last("rate_limited", "failure");
        assertThat(event.at("/event/reason").asText()).isEqualTo("password_change_user");
        assertThat(event.at("/event/category").asText()).isEqualTo("intrusion_detection");
        assertThat(event.at("/user/id").asText()).isEqualTo(member.userId());
    }

    @Test
    void everyRequestGetsAServerGeneratedId_thatAppearsInTheEventsAndTheResponse() {
        HttpResponse<String> response = signIn("nobody@test.local", "x",
                "X-Forwarded-For", freshIp(), "X-Request-Id", "client-supplied-id");

        String header = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(header).isNotEqualTo("client-supplied-id").matches("[0-9a-f-]{36}");
        assertThat(last("signin", "failure").at("/http/request/id").asText()).isEqualTo(header);
    }

    @Test
    void theUserAgent_isLoggedButCappedAndCannotInjectLines() {
        String hostile = "Mozilla/5.0 " + "x".repeat(500);

        signIn("nobody@test.local", "x", "X-Forwarded-For", freshIp(), "User-Agent", hostile);

        String agent = last("signin", "failure").at("/user_agent/original").asText();
        assertThat(agent).startsWith("Mozilla/5.0").hasSizeLessThanOrEqualTo(200);
    }

    // --- helpers -------------------------------------------------------------------------

    private record Member(String userId, String access) {
    }

    private HttpResponse<String> changePassword(String access, String current, String next) {
        return put("/api/users/me/password",
                "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next
                        + "\",\"confirmNewPassword\":\"" + next + "\"}", "Authorization", "Bearer " + access);
    }

    private Member registerMember() {
        String email = "member-" + UUID.randomUUID() + "@test.local";
        String adminAuth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        String inviteToken = json(post("/api/admin/invites", "{\"email\":\"" + email + "\"}",
                "Authorization", adminAuth)).path("data").path("token").asText();
        post("/auth/invites/" + inviteToken + "/signup",
                "{\"firstName\":\"Pat\",\"lastName\":\"Member\",\"password\":\"" + MEMBER_PASSWORD
                        + "\",\"confirmPassword\":\"" + MEMBER_PASSWORD + "\"}", "X-Forwarded-For", freshIp());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendOtpEmail(eq(email), code.capture());
        HttpResponse<String> verified = post("/auth/invites/" + inviteToken + "/verify-otp",
                "{\"code\":\"" + code.getValue() + "\"}", "X-Forwarded-For", freshIp());
        return new Member(json(verified).path("data").path("user").path("id").asText(), accessTokenOf(verified));
    }

    private List<String> lines() {
        return captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private JsonNode parse(String line) {
        try {
            return JSON.readTree(line);
        } catch (Exception e) {
            throw new IllegalStateException("Not JSON: " + line, e);
        }
    }

    private List<JsonNode> events(String action, String outcome) {
        return lines().stream().map(this::parse)
                .filter(e -> action.equals(e.at("/event/action").asText()))
                .filter(e -> outcome.equals(e.at("/event/outcome").asText()))
                .toList();
    }

    private JsonNode last(String action, String outcome) {
        List<JsonNode> found = events(action, outcome);
        assertThat(found).as("events " + action + "/" + outcome).isNotEmpty();
        return found.get(found.size() - 1);
    }

    private JsonNode only(String action, String outcome, String reason) {
        List<JsonNode> found = events(action, outcome).stream()
                .filter(e -> reason.equals(e.at("/event/reason").asText())).toList();
        assertThat(found).as(action + "/" + outcome + "/" + reason).hasSize(1);
        return found.get(0);
    }

    private void assertNothingSecretIsLogged(String... secrets) {
        for (String line : lines()) {
            for (String secret : secrets) {
                assertThat(line).as("log line must not contain a secret").doesNotContain(secret);
            }
        }
    }
}
