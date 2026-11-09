package com.mnyace.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findAllByFamilyIdAndRevokedAtIsNull(String familyId);
    List<RefreshToken> findAllByUser_IdAndRevokedAtIsNull(String userId);
    List<RefreshToken> findAllByFamilyIdAndUser_IdAndRevokedAtIsNull(String familyId, String userId);
    List<RefreshToken> findAllByUser_IdAndRevokedAtIsNullAndExpiresAtAfter(String userId, LocalDateTime now);
    /** True while the session still has a live (not revoked, not expired) token. */
    boolean existsByFamilyIdAndRevokedAtIsNullAndExpiresAtAfter(String familyId, LocalDateTime now);
    List<RefreshToken> findAllByFamilyId(String familyId);
    List<RefreshToken> findAllByFamilyIdAndUser_Id(String familyId, String userId);
    List<RefreshToken> findAllByUser_Id(String userId);
    /** Revoked rows of one session, newest revocation first - the tail is what rotation prunes. */
    List<RefreshToken> findAllByFamilyIdAndRevokedAtIsNotNullOrderByRevokedAtDesc(String familyId);
    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}