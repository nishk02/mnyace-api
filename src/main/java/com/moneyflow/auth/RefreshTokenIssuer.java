package com.moneyflow.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class RefreshTokenIssuer {
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${refresh-token.expiration-days}")
    private long expirationDays;

    public record IssuedRefreshToken(User user, String rawToken, LocalDateTime expiresAt) {}

    @Transactional
    public IssuedRefreshToken issue(User user, String familyId, String deviceLabel) {
        String rawToken = generateRawToken();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(expirationDays);

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(hash(rawToken));
        token.setFamilyId(familyId);
        token.setDeviceLabel(deviceLabel);
        token.setExpiresAt(expiresAt);
        refreshTokenRepository.save(token);

        return new IssuedRefreshToken(user, rawToken, expiresAt);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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