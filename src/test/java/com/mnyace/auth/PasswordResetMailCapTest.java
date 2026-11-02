package com.mnyace.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.verify;

// The daily caps that protect the shared mail quota: 2 per email here, 3 emails in all.
@TestPropertySource(properties = {
        "password-reset.request-cooldown-seconds=0",
        "password-reset.mail.per-email-daily=2",
        "password-reset.mail.daily-budget=3"
})
class PasswordResetMailCapTest extends PasswordResetTestSupport {
    @Test
    void perEmailCap_andThenTheGlobalBudget_stopTheMail() {
        Member capped = registerMember();
        Member other = registerMember();

        // per-email: the third request answers 200 like the others but sends nothing
        assertThat(forgot(capped.email()).statusCode()).isEqualTo(200);
        assertThat(forgot(capped.email()).statusCode()).isEqualTo(200);
        HttpResponse<String> overCap = forgot(capped.email());
        assertThat(overCap.statusCode()).isEqualTo(200);
        verify(emailService, after(400).times(2)).sendPasswordResetEmail(eq(capped.email()), anyString());

        // global: 2 + 1 rows used the budget of 3, so every address now gets the same 429
        assertThat(forgot(other.email()).statusCode()).isEqualTo(200);
        HttpResponse<String> known = forgot(other.email());
        HttpResponse<String> unknown = forgot(freshEmail());

        for (HttpResponse<String> response : java.util.List.of(known, unknown)) {
            assertThat(response.statusCode()).isEqualTo(429);
            assertThat(response.headers().firstValue("Retry-After").map(Long::parseLong).orElse(0L)).isPositive();
            assertThat(json(response).path("error").path("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
        }
        verify(emailService, after(300).times(1)).sendPasswordResetEmail(eq(other.email()), anyString());
    }
}
