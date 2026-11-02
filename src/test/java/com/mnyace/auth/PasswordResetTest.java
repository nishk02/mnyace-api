package com.mnyace.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// cooldown off and the mail caps wide, so these tests can request as many codes as they need
@TestPropertySource(properties = {
        "password-reset.request-cooldown-seconds=0",
        "password-reset.mail.per-email-daily=1000",
        "password-reset.mail.daily-budget=1000"
})
class PasswordResetTest extends PasswordResetTestSupport {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordResetCleanupJob cleanupJob;

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
    void knownAndUnknownEmails_getTheSameAnswer_andOnlyTheKnownOneIsMailed() {
        Member member = registerMember();
        String ghost = freshEmail();

        HttpResponse<String> known = forgot(member.email());
        HttpResponse<String> unknown = forgot(ghost);

        assertThat(known.statusCode()).isEqualTo(200);
        assertThat(unknown.statusCode()).isEqualTo(200);
        assertThat(json(known).path("message").asText()).isEqualTo(json(unknown).path("message").asText());
        assertThat(json(known).path("success").asBoolean()).isEqualTo(json(unknown).path("success").asBoolean());
        assertThat(json(known).has("data")).isEqualTo(json(unknown).has("data"));
        assertNoResetEmailFor(ghost);
    }

    @Test
    void theEmailedCode_resetsThePassword_withoutSigningTheUserIn() {
        Member member = registerMember();
        String code = requestCode(member.email());

        HttpResponse<String> response = reset(member.email(), code, NEW, NEW);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("data").isMissingNode() || json(response).path("data").isNull()).isTrue();
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(signIn(member.email(), OLD, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(401);
        assertThat(signIn(member.email(), NEW, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(200);
    }

    @Test
    void aReset_endsEverySession() {
        Member member = registerMember();
        String otherDevice = refreshTokenOf(signIn(member.email(), OLD, "X-Forwarded-For", freshIp()));
        String code = requestCode(member.email());

        reset(member.email(), code, NEW, NEW);

        assertThat(get("/api/accounts", "Authorization", "Bearer " + member.access()).statusCode()).isEqualTo(401);
        assertThat(refresh(member.refresh(), "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(401);
        assertThat(refresh(otherDevice, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(401);
    }

    @Test
    void aResetDoesNotTouchAnotherUsersSessions() {
        Member resetter = registerMember();
        Member bystander = registerMember();
        String code = requestCode(resetter.email());

        reset(resetter.email(), code, NEW, NEW);

        assertThat(refresh(bystander.refresh(), "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(200);
    }

    @Test
    void wrongUnknownExpiredAndUsedCodes_allGetTheSameGenericError() {
        Member member = registerMember();
        String code = requestCode(member.email());
        HttpResponse<String> wrong = reset(member.email(), wrong(code), NEW, NEW);
        HttpResponse<String> unknown = reset(freshEmail(), "123456", NEW, NEW);

        jdbc.update("update password_resets set expires_at = ?", millis(LocalDateTime.now().minusMinutes(1)));
        HttpResponse<String> expired = reset(member.email(), code, NEW, NEW);

        Member other = registerMember();
        String otherCode = requestCode(other.email());
        reset(other.email(), otherCode, NEW, NEW);
        HttpResponse<String> used = reset(other.email(), otherCode, NEW + "x", NEW + "x");

        for (HttpResponse<String> response : List.of(wrong, unknown, expired, used)) {
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json(response).path("error").path("code").asText()).isEqualTo("INVALID_OR_EXPIRED_CODE");
            assertThat(json(response).path("error").path("message").asText())
                    .isEqualTo(json(wrong).path("error").path("message").asText());
        }
        assertThat(signIn(member.email(), OLD, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(200);
    }

    @Test
    void aCodeWorksOnlyOnce() {
        Member member = registerMember();
        String code = requestCode(member.email());

        assertThat(reset(member.email(), code, NEW, NEW).statusCode()).isEqualTo(200);
        HttpResponse<String> second = reset(member.email(), code, "ThirdPassword789!", "ThirdPassword789!");

        assertThat(second.statusCode()).isEqualTo(400);
        assertThat(signIn(member.email(), NEW, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(200);
    }

    @Test
    void fiveWrongGuesses_killTheCode_evenForTheRightOne() {
        Member member = registerMember();
        String code = requestCode(member.email());

        for (int i = 0; i < 5; i++) {
            assertThat(reset(member.email(), wrong(code), NEW, NEW).statusCode()).isEqualTo(400);
        }
        HttpResponse<String> rightCode = reset(member.email(), code, NEW, NEW);

        assertThat(rightCode.statusCode()).isEqualTo(400);
        assertThat(json(rightCode).path("error").path("code").asText()).isEqualTo("INVALID_OR_EXPIRED_CODE");
        assertThat(signIn(member.email(), OLD, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(200);
    }

    @Test
    void aNewRequest_replacesTheEarlierCode() {
        Member member = registerMember();
        String first = requestCode(member.email());
        String second = requestCode(member.email());

        if (!first.equals(second)) {
            assertThat(reset(member.email(), first, NEW, NEW).statusCode()).isEqualTo(400);
        }
        assertThat(reset(member.email(), second, NEW, NEW).statusCode()).isEqualTo(200);
    }

    @Test
    void invalidPasswords_areValidationErrors_andDoNotSpendAGuess() {
        Member member = registerMember();
        String code = requestCode(member.email());

        for (int i = 0; i < 3; i++) {
            assertValidationError(reset(member.email(), code, "short1!", "short1!"));
        }
        assertValidationError(reset(member.email(), code, "a".repeat(73), "a".repeat(73)));
        // 40 characters but 80 UTF-8 bytes: BCrypt only reads the first 72 bytes
        assertValidationError(reset(member.email(), code, "é".repeat(40), "é".repeat(40)));
        assertValidationError(reset(member.email(), code, NEW, NEW + "x"));
        assertValidationError(reset(member.email(), "12345", NEW, NEW));

        assertThat(reset(member.email(), code, NEW, NEW).statusCode()).isEqualTo(200);
    }

    @Test
    void emailCaseDoesNotMatter() {
        Member member = registerMember();
        String code = requestCode(member.email());

        assertThat(reset(member.email().toUpperCase(), code, NEW, NEW).statusCode()).isEqualTo(200);
    }

    @Test
    void resetAttempts_areLimitedPerEmail_evenFromManyAddresses() {
        String ghost = freshEmail();
        int statusOnLast = 0;
        for (int i = 0; i < 11; i++) {
            statusOnLast = reset(ghost, "123456", NEW, NEW).statusCode();
        }

        assertThat(statusOnLast).isEqualTo(429);
    }

    @Test
    void theCodeHashIsNotStoredInThePlain_andNothingSecretReachesTheSecurityLog() {
        Member member = registerMember();
        String code = requestCode(member.email());
        reset(member.email(), wrong(code), NEW, NEW);
        reset(member.email(), code, NEW, NEW);

        String userId = jdbc.queryForObject("select id from users where email = ?", String.class, member.email());
        String stored = jdbc.queryForObject("select code_hash from password_resets where user_id = ?", String.class, userId);
        assertThat(stored).isNotEqualTo(code).hasSize(64);
        String log = String.join("\n", captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(log).contains("password_reset");
        assertThat(log).doesNotContain(code).doesNotContain(NEW).doesNotContain(stored).doesNotContain(member.email());
    }

    @Test
    void cleanup_deletesOnlyRowsPastTheRetentionWindow() {
        Member member = registerMember();
        requestCode(member.email());
        String userId = jdbc.queryForObject("select id from users where email = ?", String.class, member.email());
        insertRow(userId, LocalDateTime.now().minusDays(5));
        insertRow(userId, LocalDateTime.now().minusHours(5));

        long deleted = cleanupJob.deleteOld();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from password_resets where created_at < ?", Long.class,
                millis(LocalDateTime.now().minusDays(2)))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from password_resets where user_id = ?", Long.class, userId))
                .isEqualTo(2);
    }

    private void insertRow(String userId, LocalDateTime createdAt) {
        jdbc.update("insert into password_resets (id, user_id, code_hash, expires_at, attempts, created_at) values (?, ?, 'x', ?, 0, ?)",
                java.util.UUID.randomUUID().toString(), userId, millis(createdAt.plusMinutes(15)), millis(createdAt));
    }

    private static void assertValidationError(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("error").path("code").asText()).isEqualTo("VALIDATION_ERROR");
    }
}
