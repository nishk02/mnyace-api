package com.mnyace.account;


import java.math.BigDecimal;
import java.util.List;

public record AccountResponse(
        String id,
        String name,
        String type,
        BigDecimal currentBalance,
        String currency,
        String colorLabel,
        String accountSuffix,
        List<String> cardSuffixes,
        int displayOrder,
        boolean goalLinked
) {
    public static AccountResponse from(Account account, boolean goalLinked) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getCurrentBalance(),
                account.getCurrency(),
                account.getColorLabel(),
                account.getAccountSuffix(),
                account.getCardSuffixes(),
                account.getDisplayOrder(),
                goalLinked
        );
    }
}