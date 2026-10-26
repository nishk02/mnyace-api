package com.mnyace.account;

import java.math.BigDecimal;
import java.util.List;

public record AccountSummaryResponse(
        BigDecimal totalBalance,
        List<AccountResponse> accounts
) {
}
