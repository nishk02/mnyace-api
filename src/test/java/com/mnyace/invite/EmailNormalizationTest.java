package com.mnyace.invite;

import com.mnyace.auth.AuthIntegrationTestBase;
import com.mnyace.shared.email.EmailService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpResponse;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

class EmailNormalizationTest extends AuthIntegrationTestBase {
    private static final String PASSWORD = "MemberPass123!";

    @MockitoBean
    private EmailService emailService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void signin_ignoresTheCaseOfTheEmail() {
        assertThat(signIn("ADMIN@Test.Local", ADMIN_PASSWORD, "X-Forwarded-For", freshIp()).statusCode())
                .isEqualTo(200);
    }

    @Test
    void invite_forAnExistingUser_isRejectedEvenWithDifferentCase() {
        HttpResponse<String> response = invite("Admin@TEST.local");

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    void invitedEmail_isStoredLowercase_andCanSignInInAnyCase() {
        String inviteToken = json(invite("New.Member@Test.Local")).path("data").path("token").asText();
        post("/auth/invites/" + inviteToken + "/signup",
                "{\"firstName\":\"New\",\"lastName\":\"Member\",\"password\":\"" + PASSWORD
                        + "\",\"confirmPassword\":\"" + PASSWORD + "\"}", "X-Forwarded-For", freshIp());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendOtpEmail(anyString(), code.capture());

        HttpResponse<String> verified = post("/auth/invites/" + inviteToken + "/verify-otp",
                "{\"code\":\"" + code.getValue() + "\"}", "X-Forwarded-For", freshIp());

        assertThat(verified.statusCode()).isEqualTo(200);
        assertThat(json(verified).path("data").path("user").path("email").asText())
                .isEqualTo("new.member@test.local");
        assertThat(signIn("NEW.MEMBER@test.local", PASSWORD, "X-Forwarded-For", freshIp()).statusCode())
                .isEqualTo(200);
    }

    @Test
    void expiredInvite_canBeReissuedToTheSameEmail() {
        String email = "late.joiner@test.local";
        String expiredToken = json(invite(email)).path("data").path("token").asText();
        expire(expiredToken);

        HttpResponse<String> reissued = invite(email);

        assertThat(reissued.statusCode()).isEqualTo(201);
        String newToken = json(reissued).path("data").path("token").asText();
        assertThat(newToken).isNotEqualTo(expiredToken);
        assertThat(get("/auth/invites/" + expiredToken).statusCode()).isEqualTo(400);
        assertThat(get("/auth/invites/" + newToken).statusCode()).isEqualTo(200);
    }

    private void expire(String inviteToken) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery("update invites set expires_at = :t where token = :k")
                        .setParameter("t", LocalDateTime.now().minusDays(1))
                        .setParameter("k", inviteToken)
                        .executeUpdate());
    }

    private HttpResponse<String> invite(String email) {
        String adminToken = accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        return post("/api/admin/invites", "{\"email\":\"" + email + "\"}", "Authorization", "Bearer " + adminToken);
    }
}
