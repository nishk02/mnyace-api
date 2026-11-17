package com.mnyace.account;

import com.mnyace.shared.exception.ApiException;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Rules for the last digits an SMS names an account or card by. Messages name the field, never the digits. */
final class AccountSuffixes {
    static final int MAX_CARDS = 5;
    private static final Pattern ACCOUNT_DIGITS = Pattern.compile("\\d{3,4}");
    private static final Pattern CARD_DIGITS = Pattern.compile("\\d{4}");

    private AccountSuffixes() {
    }

    /** Checks shape, the type and the uniqueness among the user's other active accounts. */
    static void validate(String type, String accountSuffix, List<String> cardSuffixes, List<Account> others) {
        if (accountSuffix != null && !ACCOUNT_DIGITS.matcher(accountSuffix).matches()) {
            throw invalid("accountSuffix must be 3 or 4 digits");
        }
        if (cardSuffixes.size() > MAX_CARDS) {
            throw invalid("cardSuffixes can hold at most " + MAX_CARDS + " cards");
        }
        for (String card : cardSuffixes) {
            if (card == null || !CARD_DIGITS.matcher(card).matches()) {
                throw invalid("Each entry in cardSuffixes must be exactly 4 digits");
            }
        }
        if (new HashSet<>(cardSuffixes).size() != cardSuffixes.size()) {
            throw invalid("cardSuffixes must not repeat a card");
        }
        if ((accountSuffix != null || !cardSuffixes.isEmpty()) && !"BANK".equals(type)) {
            throw invalid("Only a BANK account can have accountSuffix or cardSuffixes");
        }
        for (Account other : others) {
            // an SMS carries the last 3 or 4 digits, so 388 and 3388 could not be told apart
            if (accountSuffix != null && other.getAccountSuffix() != null
                    && (accountSuffix.endsWith(other.getAccountSuffix()) || other.getAccountSuffix().endsWith(accountSuffix))) {
                throw invalid("accountSuffix is already used by another of your accounts");
            }
            if (cardSuffixes.stream().anyMatch(other.getCardSuffixes()::contains)) {
                throw invalid("cardSuffixes include a card already saved on another of your accounts");
            }
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("VALIDATION_ERROR", message, null);
    }
}
