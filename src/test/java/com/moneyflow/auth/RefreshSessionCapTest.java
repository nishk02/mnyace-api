package com.moneyflow.auth;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshSessionCapTest extends AuthIntegrationTestBase {
    private static final int MAX_SESSION_DAYS = 90;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void sessionStart_isCarriedAcrossRotations() {
        String ip = freshIp();
        String first = refreshTokenOf(signInAdmin("X-Forwarded-For", ip));
        String second = refreshTokenOf(refresh(first, "X-Forwarded-For", ip));

        assertThat(rowOf(second).getSessionStartedAt()).isEqualTo(rowOf(first).getSessionStartedAt());
    }

    @Test
    void sessionOlderThanTheCap_cannotBeRefreshed() {
        String ip = freshIp();
        String token = refreshTokenOf(signInAdmin("X-Forwarded-For", ip));
        startSessionDaysAgo(token, MAX_SESSION_DAYS + 1);

        assertThat(refresh(token, "X-Forwarded-For", ip).statusCode()).isEqualTo(401);
    }

    @Test
    void sessionNearTheCap_getsATokenThatEndsWithTheSession() {
        String ip = freshIp();
        String token = refreshTokenOf(signInAdmin("X-Forwarded-For", ip));
        startSessionDaysAgo(token, MAX_SESSION_DAYS - 10);
        LocalDateTime sessionEnd = rowOf(token).getSessionStartedAt().plusDays(MAX_SESSION_DAYS);

        assertThat(refresh(token, "X-Forwarded-For", ip).statusCode()).isEqualTo(200);

        RefreshToken newest = refreshTokens.findAll().stream()
                .filter(t -> t.getFamilyId().equals(rowOf(token).getFamilyId()) && t.getRevokedAt() == null)
                .findFirst().orElseThrow();
        assertThat(newest.getExpiresAt()).isBeforeOrEqualTo(sessionEnd.plus(Duration.ofSeconds(1)));
        assertThat(newest.getExpiresAt()).isAfter(LocalDateTime.now());
    }

    private RefreshToken rowOf(String rawToken) {
        return refreshTokens.findByTokenHash(sha256(rawToken)).orElseThrow();
    }

    private void startSessionDaysAgo(String rawToken, int days) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery("update refresh_tokens set session_started_at = :t where token_hash = :h")
                        .setParameter("t", LocalDateTime.now().minusDays(days))
                        .setParameter("h", sha256(rawToken))
                        .executeUpdate());
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
