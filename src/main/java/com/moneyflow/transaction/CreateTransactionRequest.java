package com.moneyflow.transaction;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CreateTransactionRequest(
        @NotNull(message = "Transaction date is required")
        LocalDate date,

        @NotNull(message = "Transaction type is required")
        TransactionType type,

        @NotBlank(message = "Category is required")
        String categoryId,

        @NotBlank(message = "From account is required")
        String accountId,

        String toAccountId,
        String toGoalId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 13, fraction = 2, message = "Amount is too large or has more than 2 decimal places")
        BigDecimal amount,

        @Size(max = 1000, message = "Notes must be at most 1000 characters")
        String notes,

        @Valid
        @Size(max = 50, message = "Too many goal allocations")
        List<GoalAllocationItem> goalAllocations
) {
}
