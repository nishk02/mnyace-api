package com.mnyace.auth;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Deletes refresh tokens that expired more than the retention window ago; revoked-but-unexpired rows stay for reuse detection. */
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${refresh-token.cleanup.retention-days}")
    private long retentionDays;

    // zone matches AppClock (IST), so "03:30" means the same wherever the server runs
    @Scheduled(cron = "${refresh-token.cleanup.cron}", zone = "Asia/Kolkata")
    public void run() {
        long deleted = deleteExpired();
        log.info("Refresh token cleanup removed {} expired rows", deleted);
    }

    @Transactional
    public long deleteExpired() {
        return refreshTokenRepository.deleteByExpiresAtBefore(LocalDateTime.now().minusDays(retentionDays));
    }
}
