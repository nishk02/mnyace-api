package com.moneyflow.account;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import com.moneyflow.transaction.GoalAllocationItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.List;

public record UpdateAccountRequest(
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @Pattern(regexp = "CASH|BANK|WALLET", message = "Type must be CASH, BANK or WALLET")
        String type,

        @DecimalMin(value = "0.0", message = "Balance cannot be negative")
        @Digits(integer = 13, fraction = 2, message = "Amount is too large or has more than 2 decimal places")
        BigDecimal currentBalance,

        @Size(max = 30, message = "Color label must be at most 30 characters")
        String colorLabel,

        @Valid
        @Size(max = 50, message = "Too many goal allocations")
        List<GoalAllocationItem> goalAllocations
) {
}
