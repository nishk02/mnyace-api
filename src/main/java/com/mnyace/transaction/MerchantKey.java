package com.mnyace.transaction;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The form a merchant name is matched in: "Swiggy ", "SWIGGY 123" and "swiggy*" all become SWIGGY.
 * Written when a transaction is created and used again when categories are looked up, and the app builds
 * the same key, so this rule is part of the API contract (BR-34).
 */
public final class MerchantKey {
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern NOISE = Pattern.compile("[0-9*#\\-_/.@]");

    private MerchantKey() {
    }

    /** Null when nothing is left after normalising, so such a merchant is never learned from. */
    public static String of(String merchant) {
        if (merchant == null) {
            return null;
        }
        String key = WHITESPACE.matcher(merchant.trim().toUpperCase(Locale.ROOT)).replaceAll(" ");
        key = NOISE.matcher(key).replaceAll("");
        key = WHITESPACE.matcher(key).replaceAll(" ").trim();
        return key.isEmpty() ? null : key;
    }
}
