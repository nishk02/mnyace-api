package com.mnyace.auth;

import java.nio.charset.StandardCharsets;
import com.mnyace.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenRevoker refreshTokenRevoker;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final SessionGate sessionGate;
    private final TransactionTemplate transactionTemplate;

    // SQLite has a single writer and fails (rather than waits) when two transactions try to
    // upgrade a read to a write at once. Rotations of the same token are therefore serialized
    // in-process; the app is a single instance, so a striped JVM lock is enough.
    private static final int LOCK_STRIPES = 64;
    private final ReentrantLock[] rotationLocks = createLocks();
    // Sign-ins of one user queue here, so counting live sessions and adding one is a single step.
    private final ReentrantLock[] signInLocks = createLocks();

    private static ReentrantLock[] createLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    /**
     * A new sign-in replaces any session still waiting beyond the cap, so at most one waits at a time. Call it
     * outside a transaction: the lock must be released only after the new session has committed.
     */
    public RefreshTokenIssuer.IssuedRefreshToken issueNewFamily(User user, String deviceLabel, ClientInfo client) {
        ReentrantLock lock = signInLocks[(user.getId().hashCode() & Integer.MAX_VALUE) % LOCK_STRIPES];
        lock.lock();
        try {
            return transactionTemplate.execute(status -> {
                sessionGate.liveSessionsOldestFirst(user.getId()).stream()
                        .skip(sessionGate.maxSessions())
                        .forEach(waiting -> deleteSession(waiting.getFamilyId()));
                return refreshTokenIssuer.issue(
                        user, UUID.randomUUID().toString(), deviceLabel, LocalDateTime.now(), client);
            });
        } finally {
            lock.unlock();
        }
    }

    public RefreshTokenIssuer.IssuedRefreshToken rotate(String presentedRawToken) {
        String tokenHash = hash(presentedRawToken);
        ReentrantLock lock = rotationLocks[(tokenHash.hashCode() & Integer.MAX_VALUE) % LOCK_STRIPES];
        lock.lock();
        try {
            RefreshTokenRevoker.CheckResult result = refreshTokenRevoker.checkAndHandleReuse(tokenHash);

            return switch (result.status()) {
                case NOT_FOUND, EXPIRED, REUSE_DETECTED, DUPLICATE_REQUEST ->
                        throw ApiException.unauthorized("Session expired. Please sign in again.");
                case VALID -> refreshTokenRevoker.rotateValid(result.validTokenId());
            };
        } finally {
            lock.unlock();
        }
    }

    /** Signing out removes the session's rows: with the whole family gone, a replayed token just reads as unknown. */
    @Transactional
    public void revokeFamilyOf(String presentedRawToken) {
        refreshTokenRepository.findByTokenHash(hash(presentedRawToken))
                .ifPresent(token -> deleteSession(token.getFamilyId()));
    }

    @Transactional
    public void revokeAllForUser(String userId) {
        refreshTokenRepository.deleteAll(refreshTokenRepository.findAllByUser_Id(userId));
    }

    private void deleteSession(String familyId) {
        refreshTokenRepository.deleteAll(refreshTokenRepository.findAllByFamilyId(familyId));
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}