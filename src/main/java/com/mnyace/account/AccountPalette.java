package com.mnyace.account;

import java.util.List;

/**
 * The colour names a user can give a wallet or a bank that is not in the list. They are the names the app already
 * knows; the app owns the hex values and the light and dark themes, so the server holds only the names.
 */
public final class AccountPalette {
    private static final List<String> KEYS = List.of(
            "blue", "teal", "green", "yellow", "orange", "red", "pink", "purple", "deep-purple", "brown");

    /** What the seeded Cash account wears. */
    public static final String CASH_KEY = "green";

    private AccountPalette() {
    }

    public static List<String> keys() {
        return KEYS;
    }

    public static boolean contains(String key) {
        return KEYS.contains(key);
    }
}
