package com.moneyflow.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenCleanupTest extends AuthIntegrationTestBase {
    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private UserRepository users;

    @Autowired
    private RefreshTokenCleanupJob cleanupJob;

    @Test
    void deletesOnlyTokensExpiredBeyondTheRetentionWindow() {
        LocalDateTime now = LocalDateTime.now();
        String longExpired = save(now.minusDays(10), null);
        String longExpiredAndRevoked = save(now.minusDays(10), now.minusDays(40));
        String recentlyExpired = save(now.minusDays(1), null);
        String activeButRevoked = save(now.plusDays(10), now.minusDays(1));
        String active = save(now.plusDays(10), null);

        long deleted = cleanupJob.deleteExpired();

        assertThat(deleted).isGreaterThanOrEqualTo(2);
        assertThat(refreshTokens.findById(longExpired)).isEmpty();
        assertThat(refreshTokens.findById(longExpiredAndRevoked)).isEmpty();
        assertThat(refreshTokens.findById(recentlyExpired)).isPresent();
        assertThat(refreshTokens.findById(activeButRevoked)).isPresent();
        assertThat(refreshTokens.findById(active)).isPresent();
    }

    @Test
    void aLiveSessionSurvivesTheCleanup() {
        String ip = freshIp();
        String token = refreshTokenOf(signInAdmin("X-Forwarded-For", ip));

        cleanupJob.deleteExpired();

        assertThat(refresh(token, "X-Forwarded-For", ip).statusCode()).isEqualTo(200);
    }

    private String save(LocalDateTime expiresAt, LocalDateTime revokedAt) {
        RefreshToken token = new RefreshToken();
        token.setUser(users.findByEmailIgnoreCase(ADMIN_EMAIL).orElseThrow());
        token.setTokenHash(UUID.randomUUID().toString());
        token.setFamilyId(UUID.randomUUID().toString());
        token.setExpiresAt(expiresAt);
        token.setRevokedAt(revokedAt);
        return refreshTokens.save(token).getId();
    }
}
