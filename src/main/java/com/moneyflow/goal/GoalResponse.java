package com.moneyflow.goal;

import com.moneyflow.shared.util.AppClock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public record GoalResponse(
        String id,
        String name,
        BigDecimal targetAmount,
        AccountSummary account,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal currentProgress,
        BigDecimal progressPercentage,
        BigDecimal plannedMonthlySavings,
        BigDecimal currentMonthlySavingsRequired,
        long monthsRemaining,
        String status,
        boolean isUpcoming,
        boolean isBehindSchedule,
        int displayOrder
) {
    public record AccountSummary(String id, String name, String colorLabel) {}

    public static GoalResponse from(Goal goal, BigDecimal creditedThisMonth) {
        AccountSummary account = new AccountSummary(
                goal.getAccount().getId(),
                goal.getAccount().getName(),
                goal.getAccount().getColorLabel());

        final BigDecimal progressPercentage = calculateProgressPercentage(goal);

        long monthsRemaining = goal.getEndDate() != null
                ? Math.max(0, monthsBetweenIgnoringDay(AppClock.today(), goal.getEndDate()))
                : 0;

        boolean isUpcoming = goal.getStartDate() != null && goal.getStartDate().isAfter(AppClock.today());

        // frozen plan, set at creation/re-plan
        BigDecimal plannedMonthlySavings = goal.getMonthlySavingsRequired();
        // live, nets this month's own credit
        BigDecimal currentMonthlySavingsRequired = calculateCurrentMonthlySavingsRequired(goal, creditedThisMonth);

        // Behind the original plan, not just "owes money this month" - a freshly-started or not-yet-started
        // goal with a valid plan simply isn't behind anything yet.
        boolean isBehindSchedule = !isUpcoming
                && plannedMonthlySavings != null
                && plannedMonthlySavings.compareTo(BigDecimal.ZERO) > 0
                && currentMonthlySavingsRequired.compareTo(plannedMonthlySavings) > 0;

        return new GoalResponse(
                goal.getId(),
                goal.getName(),
                goal.getTargetAmount(),
                account,
                goal.getStartDate(),
                goal.getEndDate(),
                goal.getCurrentProgress(),
                progressPercentage,
                plannedMonthlySavings,
                currentMonthlySavingsRequired,
                monthsRemaining,
                goal.getStatus(),
                isUpcoming,
                isBehindSchedule,
                goal.getDisplayOrder()
        );
    }

    private static BigDecimal calculateProgressPercentage(Goal goal) {
        if (goal == null
                || goal.getTargetAmount() == null
                || goal.getCurrentProgress() == null
                || goal.getTargetAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return goal.getCurrentProgress()
                .multiply(new BigDecimal("100"))
                .divide(goal.getTargetAmount(), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculateCurrentMonthlySavingsRequired(Goal goal, BigDecimal creditedThisMonth) {
        if (goal.getTargetAmount() == null || goal.getEndDate() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal currentProgress = goal.getCurrentProgress() != null ? goal.getCurrentProgress() : BigDecimal.ZERO;
        BigDecimal actualRemaining = goal.getTargetAmount().subtract(currentProgress);

        if (actualRemaining.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        long monthsRemaining = Math.max(0, monthsBetweenIgnoringDay(AppClock.today(), goal.getEndDate()));

        // Pace before this month's own credit, so it isn't subtracted twice.
        BigDecimal remainingBeforeThisMonth = actualRemaining.add(creditedThisMonth);

        BigDecimal averagePaceNeeded = monthsRemaining <= 0
                ? remainingBeforeThisMonth
                : remainingBeforeThisMonth.divide(BigDecimal.valueOf(monthsRemaining), 1, RoundingMode.HALF_UP);

        BigDecimal requiredThisMonth = averagePaceNeeded.subtract(creditedThisMonth);
        return requiredThisMonth.compareTo(BigDecimal.ZERO) > 0 ? requiredThisMonth : BigDecimal.ZERO;
    }

    /** Months between two dates, ignoring the day-of-month component. */
    private static long monthsBetweenIgnoringDay(LocalDate start, LocalDate end) {
        return ChronoUnit.MONTHS.between(start.withDayOfMonth(1), end.withDayOfMonth(1));
    }
}
