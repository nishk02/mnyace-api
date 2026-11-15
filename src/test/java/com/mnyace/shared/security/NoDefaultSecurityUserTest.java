package com.mnyace.shared.security;

import com.mnyace.auth.AuthIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;

// Spring Boot otherwise creates a user named "user" and prints its password at startup.
class NoDefaultSecurityUserTest extends AuthIntegrationTestBase {
    @Autowired
    private ApplicationContext context;

    @Test
    void noDefaultUserIsCreated() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void signingIn_stillWorks() {
        assertThat(signInAdmin("X-Forwarded-For", freshIp()).statusCode()).isEqualTo(200);
    }
}
