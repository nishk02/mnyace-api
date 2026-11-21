package com.mnyace.account;


import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

public record AccountResponse(
        String id,
        String name,
        String type,
        BigDecimal currentBalance,
        String currency,
        String colorKey,
        String colorLight,
        String colorDark,
        String bankCode,
        boolean customBank,
        String accountSuffix,
        List<String> cardSuffixes,
        int displayOrder,
        boolean goalLinked,
        Instant createdAt
) {
    public static AccountResponse from(Account account, boolean goalLinked) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getCurrentBalance(),
                account.getCurrency(),
                account.getColorKey(),
                account.getColorLight(),
                account.getColorDark(),
                account.getBankCode(),
                account.isCustomBank(),
                account.getAccountSuffix(),
                account.getCardSuffixes(),
                account.getDisplayOrder(),
                goalLinked,
                // stored as the server's local time, the same value BR-13 takes its setup date from; SQLite keeps
                // milliseconds, so the create response is cut to match what every later read returns
                account.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().truncatedTo(ChronoUnit.MILLIS)
        );
    }
}
