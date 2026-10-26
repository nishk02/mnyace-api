package com.mnyace.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class RefreshToken {
    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    // SHA-256 hex of the raw opaque token - never the raw token itself.
    @Column(name = "token_hash", nullable = false, unique = true, updatable = false)
    private String tokenHash;

    // Shared across one rotation chain (one login/device) - lets reuse
    // detection revoke the whole chain without touching other devices.
    @Column(name = "family_id", nullable = false, updatable = false)
    private String familyId;

    @Column(name = "device_label")
    private String deviceLabel;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    // When the original sign-in happened; carried across rotations to enforce the absolute session cap.
    @Column(name = "session_started_at", nullable = false, updatable = false)
    private LocalDateTime sessionStartedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public boolean isActive(LocalDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}