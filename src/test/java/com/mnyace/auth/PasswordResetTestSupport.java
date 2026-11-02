package com.mnyace.auth;

import com.mnyace.shared.email.EmailService;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** Members, forgot/reset calls and the emailed code, for the password reset tests. */
abstract class PasswordResetTestSupport extends AuthIntegrationTestBase {
    protected static final String OLD = "OldPassword123!";
    protected static final String NEW = "NewPassword456!";

    @MockitoBean
    protected EmailService emailService;

    protected record Member(String email, String access, String refresh) {
    }

    protected static String freshEmail() {
        return "member-" + UUID.randomUUID() + "@test.local";
    }

    protected HttpResponse<String> forgot(String email) {
        return forgotFrom(email, freshIp());
    }

    protected HttpResponse<String> forgotFrom(String email, String ip) {
        return post("/auth/forgot-password", "{\"email\":\"" + email + "\"}", "X-Forwarded-For", ip);
    }

    /** The column format SQLite uses for timestamps written through Hibernate: epoch milliseconds. */
    protected static long millis(java.time.LocalDateTime time) {
        return time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    protected HttpResponse<String> reset(String email, String code, String password, String confirm) {
        return post("/auth/reset-password", "{\"email\":\"" + email + "\",\"code\":\"" + code
                + "\",\"newPassword\":\"" + password + "\",\"confirmNewPassword\":\"" + confirm + "\"}",
                "X-Forwarded-For", freshIp());
    }

    /** Requests a reset and returns the code from the email the (mocked) mailer was asked to send. */
    protected String requestCode(String email) {
        Mockito.clearInvocations(emailService);
        assertThat(forgot(email).statusCode()).isEqualTo(200);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, timeout(3000).atLeastOnce()).sendPasswordResetEmail(eq(email), code.capture());
        return code.getValue();
    }

    protected void assertNoResetEmailFor(String email) {
        verify(emailService, after(300).never()).sendPasswordResetEmail(eq(email), org.mockito.ArgumentMatchers.anyString());
    }

    /** A code that is certainly not the right one. */
    protected static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    /** Creates a member through the invite flow and returns their email and first session. */
    protected Member registerMember() {
        String email = freshEmail();
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
