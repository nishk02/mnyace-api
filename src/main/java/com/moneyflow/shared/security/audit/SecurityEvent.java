package com.moneyflow.shared.security.audit;

import java.util.Locale;

/** The catalogue of security events, named after the Elastic Common Schema category, action and outcome fields. */
public enum SecurityEvent {
    SIGNIN_SUCCESS(Category.AUTHENTICATION, "signin", Outcome.SUCCESS),
    SIGNIN_FAILURE(Category.AUTHENTICATION, "signin", Outcome.FAILURE),
    SIGNOUT_ALL(Category.AUTHENTICATION, "signout_all", Outcome.SUCCESS),
    RATE_LIMITED(Category.INTRUSION_DETECTION, "rate_limited", Outcome.FAILURE),
    REFRESH_TOKEN_REUSE(Category.INTRUSION_DETECTION, "refresh_token_reuse", Outcome.FAILURE),
    PASSWORD_CHANGED(Category.IAM, "password_change", Outcome.SUCCESS),
    PASSWORD_CHANGE_FAILED(Category.IAM, "password_change", Outcome.FAILURE),
    OTP_LOCKOUT(Category.IAM, "otp_lockout", Outcome.FAILURE),
    INVITE_CREATED(Category.IAM, "invite_create", Outcome.SUCCESS);

    public enum Category { AUTHENTICATION, IAM, INTRUSION_DETECTION }

    public enum Outcome { SUCCESS, FAILURE }

    private final Category category;
    private final String action;
    private final Outcome outcome;

    SecurityEvent(Category category, String action, Outcome outcome) {
        this.category = category;
        this.action = action;
        this.outcome = outcome;
    }

    String category() {
        return category.name().toLowerCase(Locale.ROOT);
    }

    String action() {
        return action;
    }

    String outcome() {
        return outcome.name().toLowerCase(Locale.ROOT);
    }
}
