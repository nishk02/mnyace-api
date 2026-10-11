package com.moneyflow.goal;

import com.moneyflow.shared.util.AppClock;
import com.moneyflow.transaction.TransactionGoalAllocationRepository;
import com.moneyflow.transaction.TransactionRepository;
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
        return deposited.subtract(withdrawn);
    }
}