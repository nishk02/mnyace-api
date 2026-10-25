package com.moneyflow.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Refuses to boot prod with the public dev JWT secret or a weak one. */
@Component
@Profile("prod")
public class ProdSecretsGuard {
    static final String DEV_DEFAULT_SECRET = "moneyflow-local-dev-secret-key-2026-not-for-prod";
    static final int MIN_SECRET_BYTES = 32;

    public ProdSecretsGuard(@Value("${jwt.secret}") String jwtSecret) {
        validate(jwtSecret);
    }

    static void validate(String jwtSecret) {
        if (DEV_DEFAULT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException("jwt.secret is the public dev default; set JWT_SECRET for prod");
        }
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes in prod");
        }
    }
}
