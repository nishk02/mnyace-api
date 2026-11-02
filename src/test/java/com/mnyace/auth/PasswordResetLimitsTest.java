package com.mnyace.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// default 60-second cooldown; the per-address short window is raised so only the daily cap can trip
@TestPropertySource(properties = {
        "rate-limit.forgot-password.ip.capacity=1000",
        "rate-limit.forgot-password.ip-daily.capacity=3",
        "password-reset.mail.per-email-daily=1000",
        "password-reset.mail.daily-budget=1000"
})
class PasswordResetLimitsTest extends PasswordResetTestSupport {
    @Test
    void asecondRequestInsideTheCooldown_getsTheSameAnswer_butNoSecondEmail() {
        Member member = registerMember();

        HttpResponse<String> first = forgot(member.email());
        HttpResponse<String> second = forgot(member.email());

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json(second).path("message").asText()).isEqualTo(json(first).path("message").asText());
        verify(emailService, after(400).times(1)).sendPasswordResetEmail(eq(member.email()), anyString());
    }

    @Test
    void oneAddress_isLimitedToTheDailyNumberOfRequests() {
        String ip = freshIp();
        for (int i = 0; i < 3; i++) {
            assertThat(forgotFrom(freshEmail(), ip).statusCode()).isEqualTo(200);
        }

        HttpResponse<String> fourth = forgotFrom(freshEmail(), ip);

        assertThat(fourth.statusCode()).isEqualTo(429);
        assertThat(fourth.headers().firstValue("Retry-After")).isPresent();
        assertThat(json(fourth).path("error").path("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
    }
}
