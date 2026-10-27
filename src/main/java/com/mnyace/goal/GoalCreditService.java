package com.mnyace.goal;

import com.mnyace.shared.util.AppClock;
import com.mnyace.shared.util.Money;
import com.mnyace.transaction.TransactionGoalAllocationRepository;
import com.mnyace.transaction.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class GoalCreditService {

    private final TransactionRepository transactionRepository;
    private final TransactionGoalAllocationRepository transactionGoalAllocationRepository;

    /** Net amount actually credited to this goal in the current calendar month - deposits minus withdrawals. */
    public BigDecimal creditedThisMonth(Goal goal) {
        LocalDate now = AppClock.today();
        BigDecimal deposited = transactionRepository.sumGoalCreditByGoalIdAndCalendarMonth(
                goal.getId(), now.getYear(), now.getMonthValue());
        BigDecimal withdrawn = transactionGoalAllocationRepository.sumByGoalIdAndCalendarMonth(
                goal.getId(), now.getYear(), now.getMonthValue());
        return Money.round(deposited).subtract(Money.round(withdrawn));
    }
}