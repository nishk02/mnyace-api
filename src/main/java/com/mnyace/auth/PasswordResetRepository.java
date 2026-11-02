package com.mnyace.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PasswordResetRepository extends JpaRepository<PasswordReset, String> {
    /** Every row is one email sent, so these counts are the daily mail budget. */
    long countByCreatedAtGreaterThanEqual(LocalDateTime since);

    long countByUser_IdAndCreatedAtGreaterThanEqual(String userId, LocalDateTime since);

    Optional<PasswordReset> findFirstByUser_IdOrderByCreatedAtDesc(String userId);

    Optional<PasswordReset> findFirstByUser_IdAndUsedAtIsNullOrderByCreatedAtDesc(String userId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordReset r set r.usedAt = :now where r.user.id = :userId and r.usedAt is null")
    int closeOpenCodes(@Param("userId") String userId, @Param("now") LocalDateTime now);

    /** Spends one guess; 0 rows means the cap is reached or the code is already used. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordReset r set r.attempts = r.attempts + 1 where r.id = :id and r.attempts < :max and r.usedAt is null")
    int consumeAttempt(@Param("id") String id, @Param("max") int max);

    /** Marks the code used; 0 rows means a parallel request already did. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordReset r set r.usedAt = :now where r.id = :id and r.usedAt is null")
    int markUsed(@Param("id") String id, @Param("now") LocalDateTime now);

    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
