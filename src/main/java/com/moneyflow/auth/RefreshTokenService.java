package com.moneyflow.auth;

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

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenRevoker refreshTokenRevoker;
    private final RefreshTokenIssuer refreshTokenIssuer;

    public RefreshTokenIssuer.IssuedRefreshToken issueNewFamily(User user, String deviceLabel) {
        return refreshTokenIssuer.issue(user, UUID.randomUUID().toString(), deviceLabel);
    }

    public RefreshTokenIssuer.IssuedRefreshToken rotate(String presentedRawToken) {
        String tokenHash = hash(presentedRawToken);
        RefreshTokenRevoker.CheckResult result = refreshTokenRevoker.checkAndHandleReuse(tokenHash);

        return switch (result.status()) {
            case NOT_FOUND, EXPIRED, REUSE_DETECTED ->
                    throw ApiException.unauthorized("Session expired. Please sign in again.");
            case VALID -> refreshTokenRevoker.rotateValid(result.validTokenId());
        };
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
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}