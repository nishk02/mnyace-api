package com.mnyace.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findAllByFamilyIdAndRevokedAtIsNull(String familyId);
    List<RefreshToken> findAllByUser_IdAndRevokedAtIsNull(String userId);
    List<RefreshToken> findAllByUser_IdAndRevokedAtIsNullAndExpiresAtAfter(String userId, LocalDateTime now);
    /** True while the session still has a live (not revoked, not expired) token. */
    boolean existsByFamilyIdAndRevokedAtIsNullAndExpiresAtAfter(String familyId, LocalDateTime now);
    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}