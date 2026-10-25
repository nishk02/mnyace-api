package com.moneyflow.auth;

import com.moneyflow.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RefreshTokenRevoker {
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenIssuer refreshTokenIssuer;

    public enum Status { NOT_FOUND, REUSE_DETECTED, DUPLICATE_REQUEST, EXPIRED, VALID }

    // A token rotated this recently was most likely sent twice by the same client (double-fired
    // refresh), not replayed by a thief, so it is refused without revoking the whole family.
    private static final Duration DUPLICATE_REQUEST_WINDOW = Duration.ofSeconds(5);

    public record CheckResult(Status status, String validTokenId) {
        public static CheckResult of(Status status) { return new CheckResult(status, null); }
        public static CheckResult valid(String id) { return new CheckResult(Status.VALID, id); }
    }

    @Transactional
    public CheckResult checkAndHandleReuse(String tokenHash) {
        RefreshToken current = refreshTokenRepository.findByTokenHash(tokenHash).orElse(null);
        if (current == null) {
            return CheckResult.of(Status.NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        if (current.getRevokedAt() != null) {
            if (current.getRevokedAt().isAfter(now.minus(DUPLICATE_REQUEST_WINDOW))) {
                return CheckResult.of(Status.DUPLICATE_REQUEST);
            }
            revokeFamily(current.getFamilyId(), now);
            return CheckResult.of(Status.REUSE_DETECTED);
        }
        if (current.getExpiresAt().isBefore(now)) {
            return CheckResult.of(Status.EXPIRED);
        }
        return CheckResult.valid(current.getId());
    }

    private void revokeFamily(String familyId, LocalDateTime now) {
        List<RefreshToken> active = refreshTokenRepository.findAllByFamilyIdAndRevokedAtIsNull(familyId);
        active.forEach(t -> t.setRevokedAt(now));
        refreshTokenRepository.saveAll(active);
    }

    @Transactional
    public RefreshTokenIssuer.IssuedRefreshToken rotateValid(String currentTokenId) {
        RefreshToken current = refreshTokenRepository.findById(currentTokenId)
                .orElseThrow(() -> ApiException.unauthorized("Session expired. Please sign in again."));
        current.setRevokedAt(LocalDateTime.now());
        refreshTokenRepository.save(current);

        User user = current.getUser();
        Hibernate.initialize(user);

        return refreshTokenIssuer.issue(user, current.getFamilyId(), current.getDeviceLabel());
    }
}