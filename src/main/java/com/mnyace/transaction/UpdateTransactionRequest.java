package com.mnyace.transaction;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record UpdateTransactionRequest(
        LocalDate date,

        String categoryId,

        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 13, fraction = 2, message = "Amount is too large or has more than 2 decimal places")
        BigDecimal amount,

        @Size(max = 250, message = "Notes must be at most 250 characters")
        String notes,

        @Valid
        @Size(max = 50, message = "Too many goal allocations")
        List<GoalAllocationItem> goalAllocations
) {
}
