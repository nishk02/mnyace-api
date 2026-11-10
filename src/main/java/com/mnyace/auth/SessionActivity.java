package com.mnyace.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/** Records when a session was last used, writing at most once per throttle window so busy clients stay cheap. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionActivity {
    private final RefreshTokenRepository refreshTokenRepository;
    private final PlatformTransactionManager transactionManager;

    @Value("${session.last-active-throttle-seconds}")
    private long throttleSeconds;

    /** Never throws: a missed heartbeat must not fail the request (SQLite allows one writer at a time). */
    public void touch(RefreshToken token) {
        LocalDateTime now = LocalDateTime.now();
        if (token.getLastActiveAt().isAfter(now.minusSeconds(throttleSeconds))) {
            return;
        }
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    refreshTokenRepository.findById(token.getId())
                            .filter(row -> row.getRevokedAt() == null)
                            .ifPresent(row -> {
                                row.setLastActiveAt(now);
                                refreshTokenRepository.save(row);
                            }));
        } catch (DataAccessException e) {
            log.debug("Could not record session activity for {}", token.getFamilyId(), e);
        }
    }
}
