package com.mnyace.auth;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Deletes reset codes older than the retention window; the daily mail budget is counted from these rows, so keep at least 2 days. */
@Component
@RequiredArgsConstructor
public class PasswordResetCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetCleanupJob.class);

    private final PasswordResetRepository passwordResetRepository;

    @Value("${password-reset.cleanup.retention-days}")
    private long retentionDays;

    // zone matches AppClock (IST), so "03:45" means the same wherever the server runs
    @Scheduled(cron = "${password-reset.cleanup.cron}", zone = "Asia/Kolkata")
    public void run() {
        long deleted = deleteOld();
        log.info("Password reset cleanup removed {} old rows", deleted);
    }

    @Transactional
    public long deleteOld() {
        return passwordResetRepository.deleteByCreatedAtBefore(LocalDateTime.now().minusDays(retentionDays));
    }
}
