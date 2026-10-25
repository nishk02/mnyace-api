package com.moneyflow.shared.util;

import java.util.Locale;

/** Trims and lowercases emails (Locale.ROOT, so the result is the same on every machine). */
public final class EmailNormalizer {
    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
