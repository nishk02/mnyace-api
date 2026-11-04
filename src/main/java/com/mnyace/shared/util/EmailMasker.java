package com.mnyace.shared.util;

/** Masks an email for logs: the first character of the name and the domain stay, e.g. n***@gmail.com. */
public final class EmailMasker {
    private EmailMasker() {
    }

    public static String mask(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at < 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
