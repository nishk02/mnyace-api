package com.moneyflow.auth;

import java.nio.charset.StandardCharsets;
import com.moneyflow.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenRevoker refreshTokenRevoker;
    private final RefreshTokenIssuer refreshTokenIssuer;

    // SQLite has a single writer and fails (rather than waits) when two transactions try to
    // upgrade a read to a write at once. Rotations of the same token are therefore serialized
    // in-process; the app is a single instance, so a striped JVM lock is enough.
    private static final int LOCK_STRIPES = 64;
    private final ReentrantLock[] rotationLocks = createLocks();

    private static ReentrantLock[] createLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    public RefreshTokenIssuer.IssuedRefreshToken issueNewFamily(User user, String deviceLabel) {
        return refreshTokenIssuer.issue(user, UUID.randomUUID().toString(), deviceLabel, LocalDateTime.now());
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

    @Transactional
    public void revokeFamilyOf(String presentedRawToken) {
        refreshTokenRepository.findByTokenHash(hash(presentedRawToken)).ifPresent(token -> {
            LocalDateTime now = LocalDateTime.now();
            List<RefreshToken> active = refreshTokenRepository.findAllByFamilyIdAndRevokedAtIsNull(token.getFamilyId());
            active.forEach(t -> t.setRevokedAt(now));
            refreshTokenRepository.saveAll(active);
        });
    }

    @Transactional
    public void revokeAllForUser(String userId) {
        LocalDateTime now = LocalDateTime.now();
        List<RefreshToken> active = refreshTokenRepository.findAllByUser_IdAndRevokedAtIsNull(userId);
        active.forEach(t -> t.setRevokedAt(now));
        refreshTokenRepository.saveAll(active);
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