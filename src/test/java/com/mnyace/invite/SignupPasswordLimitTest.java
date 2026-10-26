package com.mnyace.invite;

import com.mnyace.auth.AuthIntegrationTestBase;
import com.mnyace.shared.email.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SignupPasswordLimitTest extends AuthIntegrationTestBase {
    @MockitoBean
    private EmailService emailService;

    @Test
    void aPasswordOf72Characters_isAccepted() {
        assertThat(signup("a".repeat(72)).statusCode()).isEqualTo(200);
    }

    @Test
    void aPasswordOver72Characters_isABadRequestNotAServerError() {
        assertThat(signup("a".repeat(73)).statusCode()).isEqualTo(400);
    }

    @Test
    void aPasswordOver72Bytes_isABadRequestEvenWhenItIsUnder72Characters() {
        // 40 characters, 80 UTF-8 bytes
        assertThat(signup("é".repeat(40)).statusCode()).isEqualTo(400);
    }

    @Test
    void aPasswordUnder8Characters_isStillRejected() {
        assertThat(signup("short1!").statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> signup(String password) {
        String email = "member-" + UUID.randomUUID() + "@test.local";
        String adminAuth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        String inviteToken = json(post("/api/admin/invites", "{\"email\":\"" + email + "\"}",
                "Authorization", adminAuth)).path("data").path("token").asText();
        return post("/auth/invites/" + inviteToken + "/signup",
                "{\"firstName\":\"Pat\",\"lastName\":\"Member\",\"password\":\"" + password
                        + "\",\"confirmPassword\":\"" + password + "\"}", "X-Forwarded-For", freshIp());
    }
}
