package com.mnyace.invite;

import com.mnyace.auth.AuthIntegrationTestBase;
import com.mnyace.shared.email.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

class InviteOtpTest extends AuthIntegrationTestBase {
    private static final String PASSWORD = "MemberPass123!";

    @MockitoBean
    private EmailService emailService;

    @Test
    void fullFlow_createsAMemberWhoCanSignIn() {
        String email = uniqueEmail();
        String inviteToken = createInvite(email);

        assertThat(submitSignup(inviteToken).statusCode()).isEqualTo(200);
        String code = lastOtpFor(email);

        HttpResponse<String> verified = verifyOtp(inviteToken, code);
        assertThat(verified.statusCode()).isEqualTo(200);
        assertThat(accessTokenOf(verified)).isNotBlank();

        assertThat(signIn(email, PASSWORD, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(200);
    }

    @Test
    void wrongCode_isRefused() {
        String email = uniqueEmail();
        String inviteToken = createInvite(email);
        submitSignup(inviteToken);

        assertThat(verifyOtp(inviteToken, wrong(lastOtpFor(email))).statusCode()).isEqualTo(401);
    }

    @Test
    void guessCap_appliesEvenToTheCorrectCode() {
        String email = uniqueEmail();
        String inviteToken = createInvite(email);
        submitSignup(inviteToken);
        String code = lastOtpFor(email);

        for (int i = 0; i < 10; i++) {
            assertThat(verifyOtp(inviteToken, wrong(code)).statusCode()).isEqualTo(401);
        }

        assertThat(verifyOtp(inviteToken, code).statusCode()).isEqualTo(400);
    }

    @Test
    void parallelWrongGuesses_cannotExceedTheCap() throws Exception {
        String email = uniqueEmail();
        String inviteToken = createInvite(email);
        submitSignup(inviteToken);
        String wrongCode = wrong(lastOtpFor(email));

        int callers = 30;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Callable<HttpResponse<String>> call = () -> {
                start.await();
                return verifyOtp(inviteToken, wrongCode);
            };
            futures.add(pool.submit(call));
        }
        start.countDown();

        int evaluated = 0;
        for (Future<HttpResponse<String>> f : futures) {
            if (f.get().statusCode() == 401) {
                evaluated++;
            }
        }
        pool.shutdown();

        assertThat(evaluated).isLessThanOrEqualTo(10);
    }

    private String createInvite(String email) {
        String adminToken = accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        HttpResponse<String> response = post("/api/admin/invites", "{\"email\":\"" + email + "\"}",
                "Authorization", "Bearer " + adminToken);
        assertThat(response.statusCode()).isEqualTo(201);
        return json(response).path("data").path("token").asText();
    }

    private HttpResponse<String> submitSignup(String inviteToken) {
        return post("/auth/invites/" + inviteToken + "/signup",
                "{\"firstName\":\"Test\",\"lastName\":\"Member\",\"password\":\"" + PASSWORD
                        + "\",\"confirmPassword\":\"" + PASSWORD + "\"}",
                "X-Forwarded-For", freshIp());
    }

    private HttpResponse<String> verifyOtp(String inviteToken, String code) {
        return post("/auth/invites/" + inviteToken + "/verify-otp", "{\"code\":\"" + code + "\"}",
                "X-Forwarded-For", freshIp());
    }

    private String lastOtpFor(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendOtpEmail(eq(email), code.capture());
        return code.getValue();
    }

    private static String wrong(String code) {
        char last = code.charAt(5);
        return code.substring(0, 5) + (last == '0' ? '1' : '0');
    }

    private static String uniqueEmail() {
        return "member-" + UUID.randomUUID() + "@test.local";
    }
}
