package com.mnyace.goal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateGoalRequest(
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @DecimalMin(value = "1.0", message = "Target amount cannot be negative")
        @Digits(integer = 13, fraction = 2, message = "Amount is too large or has more than 2 decimal places")
        BigDecimal targetAmount,

        String accountId,

        LocalDate endDate
) {
}
