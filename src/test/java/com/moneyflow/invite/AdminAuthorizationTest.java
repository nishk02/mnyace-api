package com.moneyflow.invite;

import com.moneyflow.auth.AuthIntegrationTestBase;
import com.moneyflow.shared.email.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthorizationTest extends AuthIntegrationTestBase {
    @MockitoBean
    private EmailService emailService;

    @Test
    void createInvite_withoutToken_isUnauthorized() {
        assertThat(post("/api/admin/invites", "{\"email\":\"a@test.local\"}").statusCode()).isEqualTo(401);
    }

    @Test
    void createInvite_asAdmin_isCreated() {
        String token = accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));

        var response = post("/api/admin/invites", "{\"email\":\"a@test.local\"}", "Authorization", "Bearer " + token);

        assertThat(response.statusCode()).isEqualTo(201);
    }
}
