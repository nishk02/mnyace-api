package com.mnyace.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findAllByFamilyIdAndRevokedAtIsNull(String familyId);
    List<RefreshToken> findAllByUser_IdAndRevokedAtIsNull(String userId);
    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}