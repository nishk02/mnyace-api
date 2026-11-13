package com.mnyace.invite;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface InviteRepository extends JpaRepository<Invite, String> {
    Optional<Invite> findByToken(String token);
    List<Invite> findAllByEmailIgnoreCase(String email);

    /** Drops every other invite for this email, so a completed sign-up leaves no usable link (or staged password hash) behind. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Invite i where lower(i.email) = lower(:email) and i.id <> :keepId")
    int deleteOthersForEmail(@Param("email") String email, @Param("keepId") String keepId);

    /** Atomically spends one OTP guess; returns 0 once none are left, so parallel requests can't exceed the cap. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Invite i set i.otpAttempts = i.otpAttempts + 1 where i.id = :id and i.otpAttempts < :max")
    int consumeOtpAttempt(@Param("id") String id, @Param("max") int max);
}
