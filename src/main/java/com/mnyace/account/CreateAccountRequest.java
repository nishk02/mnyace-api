package com.mnyace.account;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.List;

public record CreateAccountRequest(
        // required unless a listed bank supplies it; AccountBankResolver enforces that
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @NotBlank(message = "Account type is required")
        @Pattern(regexp = "BANK|WALLET", message = "Type must be BANK or WALLET; every user already has a Cash account")
        String type,

        @DecimalMin(value = "0.0", message = "Opening balance cannot be negative")
        @Digits(integer = 13, fraction = 2, message = "Amount is too large or has more than 2 decimal places")
        BigDecimal currentBalance,

        // a key from the account palette (GET /accounts/colors); not accepted for a listed bank, whose colour is the catalog's
        @Size(max = 30, message = "Color key must be at most 30 characters")
        String colorKey,

        // BANK only; a catalog code, or the user's own code together with customBank
        String bankCode,

        Boolean customBank,

        // required for BANK; digits are checked by AccountSuffixes so create and update answer alike
        String accountSuffix,

        @Size(max = 20, message = "Too many card numbers")
        List<String> cardSuffixes
) {
}