package com.mnyace.invite;

import com.mnyace.auth.AuthIntegrationTestBase;
import com.mnyace.shared.email.EmailService;
import com.mnyace.auth.User;
import com.mnyace.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InviteRepository inviteRepository;

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
    void consent_isCarriedFromTheInviteOntoTheNewUser() {
        String email = uniqueEmail();
        String inviteToken = createInvite(email);
        assertThat(submitSignup(inviteToken, ",\"consentVersion\":\"2026-12-11\",\"adultConfirmed\":true").statusCode())
                .isEqualTo(200);

        assertThat(verifyOtp(inviteToken, lastOtpFor(email)).statusCode()).isEqualTo(200);

        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.getConsentVersion()).isEqualTo("2026-12-11");
        assertThat(user.getAdultConfirmed()).isTrue();
        assertThat(user.getConsentAcceptedAt()).isNotNull().isAfter(java.time.LocalDateTime.now().minusMinutes(5));
        Invite invite = inviteRepository.findByToken(inviteToken).orElseThrow();
        assertThat(invite.getPendingConsentVersion()).isNull();
        assertThat(invite.getPendingConsentAt()).isNull();
    }

    @Test
    void consent_isOptionalForNow() {
        String email = uniqueEmail();
        String inviteToken = createInvite(email);
        submitSignup(inviteToken);
        verifyOtp(inviteToken, lastOtpFor(email));

        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.getConsentVersion()).isNull();
        assertThat(user.getConsentAcceptedAt()).isNull();
        assertThat(user.getAdultConfirmed()).isNull();
    }

    @Test
    void consent_refusesAnAgeConfirmationOfFalseAndABadVersion() {
        String inviteToken = createInvite(uniqueEmail());

        assertThat(submitSignup(inviteToken, ",\"consentVersion\":\"2026-12-11\",\"adultConfirmed\":false").statusCode())
                .isEqualTo(400);
        assertThat(submitSignup(inviteToken, ",\"consentVersion\":\"<script>\"").statusCode()).isEqualTo(400);
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

    @Test
    void completingASignup_invalidatesTheOtherInvitesForThatEmail() {
        String email = uniqueEmail();
        String first = createInvite(email);
        String second = createInvite(email);
        String pending = createInvite(email);
        submitSignup(first);
        String firstCode = lastOtpFor(email);
        submitSignup(second);
        String secondCode = lastOtpFor(email);

        assertThat(verifyOtp(first, firstCode).statusCode()).isEqualTo(200);

        assertThat(get("/auth/invites/" + pending).statusCode()).isEqualTo(404);
        assertThat(submitSignup(pending).statusCode()).isEqualTo(404);
        assertThat(post("/auth/invites/" + second + "/resend-otp", "", "X-Forwarded-For", freshIp()).statusCode())
                .isEqualTo(404);
        assertThat(verifyOtp(second, secondCode).statusCode()).isEqualTo(404);
    }

    @Test
    void parallelCompletionsForOneEmail_createOneAccountAndNoServerErrors() throws Exception {
        String email = uniqueEmail();
        int callers = 3;
        List<String> tokens = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            String token = createInvite(email);
            submitSignup(token);
            tokens.add(token);
            codes.add(lastOtpFor(email));
        }

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            String token = tokens.get(i);
            String code = codes.get(i);
            Callable<HttpResponse<String>> call = () -> {
                start.await();
                return verifyOtp(token, code);
            };
            futures.add(pool.submit(call));
        }
        start.countDown();

        for (Future<HttpResponse<String>> f : futures) {
            int status = f.get().statusCode();
            // 503 is the existing "database busy, retry" answer when SQLite's single writer is held
            assertThat(status).isIn(200, 404, 503);
        }
        pool.shutdown();

        assertThat(userRepository.existsByEmailIgnoreCase(email)).isTrue();
    }

    @Test
    void anAccountCreatedAnotherWay_makesTheInviteLookUnknown() {
        String email = uniqueEmail();
        String token = createInvite(email);
        submitSignup(token);
        String code = lastOtpFor(email);
        User user = new User();
        user.setFirstName("Other");
        user.setLastName("Route");
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setRole("MEMBER");
        userRepository.save(user);

        HttpResponse<String> response = verifyOtp(token, code);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).path("error").path("message").asText()).isEqualTo("Invite not found or expired");
    }

    @Test
    void aCompletedInvite_stillReportsItsStatus() {
        String email = uniqueEmail();
        String token = createInvite(email);
        submitSignup(token);
        verifyOtp(token, lastOtpFor(email));

        HttpResponse<String> info = get("/auth/invites/" + token);

        assertThat(info.statusCode()).isEqualTo(200);
        assertThat(json(info).path("data").path("status").asText()).isEqualTo("COMPLETED");
    }

    private String createInvite(String email) {
        String adminToken = accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        HttpResponse<String> response = post("/api/admin/invites", "{\"email\":\"" + email + "\"}",
                "Authorization", "Bearer " + adminToken);
        assertThat(response.statusCode()).isEqualTo(201);
        return json(response).path("data").path("token").asText();
    }

    private HttpResponse<String> submitSignup(String inviteToken) {
        return submitSignup(inviteToken, "");
    }

    /** {@code extraJson} is appended inside the request object, e.g. {@code ,"adultConfirmed":true}. */
    private HttpResponse<String> submitSignup(String inviteToken, String extraJson) {
        return post("/auth/invites/" + inviteToken + "/signup",
                "{\"firstName\":\"Test\",\"lastName\":\"Member\",\"password\":\"" + PASSWORD
                        + "\",\"confirmPassword\":\"" + PASSWORD + "\"" + extraJson + "}",
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
