package com.mnyace.shared.util;

import java.nio.charset.StandardCharsets;

/** BCrypt refuses to encode more than 72 bytes and silently ignores them when matching, so passwords are capped there. */
public final class PasswordRules {
    public static final int MAX_BYTES = 72;

    private PasswordRules() {
    }

    public static boolean isTooLong(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }
}
