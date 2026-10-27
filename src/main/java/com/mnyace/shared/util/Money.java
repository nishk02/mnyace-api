package com.mnyace.shared.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    private Money() {
    }

    /** Rounds to 2 decimals using banker's rounding (HALF_EVEN); null becomes zero. */
    public static BigDecimal round(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO.setScale(2) : amount.setScale(2, RoundingMode.HALF_EVEN);
    }
}
