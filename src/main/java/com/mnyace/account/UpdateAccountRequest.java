package com.mnyace.account;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import com.mnyace.transaction.GoalAllocationItem;
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

        // a key from the account palette (GET /accounts/colors); not accepted for a listed bank, whose colour is the catalog's
        @Size(max = 30, message = "Color key must be at most 30 characters")
        String colorKey,

        // Omitted leaves the bank as is; BANK only
        String bankCode,

        Boolean customBank,

        // Omitted (Java null) leaves it as is; an explicit JSON null arrives as a NullNode and clears it.
        // A JsonNode is the one way to tell the two apart without an extra library.
        JsonNode accountSuffix,

        // Replaces the whole list; omitted (null) leaves it as is.
        @Size(max = 20, message = "Too many card numbers")
        List<String> cardSuffixes,

        @Valid
        @Size(max = 50, message = "Too many goal allocations")
        List<GoalAllocationItem> goalAllocations
) {
}
