package com.moneyflow.account;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank(message = "Account name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @NotBlank(message = "Account type is required")
        @Pattern(regexp = "CASH|BANK|WALLET", message = "Type must be CASH, BANK, or WALLET")
        String type,

        @DecimalMin(value = "0.0", message = "Opening balance cannot be negative")
        @Digits(integer = 13, fraction = 2, message = "Amount is too large or has more than 2 decimal places")
        BigDecimal currentBalance,

        @Size(max = 30, message = "Color label must be at most 30 characters")
        String colorLabel
) {
}