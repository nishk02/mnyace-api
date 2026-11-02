package com.mnyace.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** HMAC of a reset code under a key derived from the JWT secret, so a leaked database alone cannot be brute-forced. */
@Component
public class PasswordResetCodeHasher {
    private static final String HMAC = "HmacSHA256";

    private final byte[] key;

    public PasswordResetCodeHasher(@Value("${jwt.secret}") String jwtSecret) {
        this.key = hmac(jwtSecret.getBytes(StandardCharsets.UTF_8), "password-reset-code");
    }

    /** The user id is part of the input, so the same code never gives the same hash for two users. */
    public String hash(String userId, String code) {
        return HexFormat.of().formatHex(hmac(key, userId + ":" + code));
    }

    public boolean matches(String userId, String code, String storedHash) {
        return MessageDigest.isEqual(
                hash(userId, code).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC not available", e);
        }
    }
}
