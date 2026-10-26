package com.mnyace.dashboard;

import com.mnyace.account.Account;
import com.mnyace.account.AccountRepository;
import com.mnyace.account.AccountResponse;
import com.mnyace.auth.User;
import com.mnyace.auth.UserRepository;
import com.mnyace.category.Category;
import com.mnyace.goal.Goal;
import com.mnyace.goal.GoalCreditService;
import com.mnyace.goal.GoalRepository;
import com.mnyace.goal.GoalResponse;
import com.mnyace.shared.exception.ApiException;
import com.mnyace.shared.util.AppClock;
import com.mnyace.transaction.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final GoalRepository goalRepository;
    private final GoalAllocationService goalAllocationService;
    private final GoalCreditService goalCreditService;

    private static final List<String> MOTIVATIONAL_QUOTES = List.of(
            "Savings is a Habit. Keeping track is the Key.",
            "You can make money two ways, make more or spend less.",
            "A budget is telling your money where to go.",
            "Do not save what is left after spending; spend what is left after saving.",
            "Financial freedom is available to those who learn about it and work for it."
    );
    private static final BigDecimal NEGLIGIBLE_REMAINDER = new BigDecimal("1.00");

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        // Goals
        List<Goal> activeGoals = goalRepository.findByUserIdAndActiveTrueOrderByDisplayOrderAsc(userId);
        Set<String> goalLinkedAccountIds = activeGoals.stream()
                .filter(g -> !"COMPLETED".equals(g.getStatus()))
                .map(g -> g.getAccount().getId())
                .collect(Collectors.toSet());

        // Accounts
        List<Account> accounts = accountRepository.findByUserIdAndActiveTrue(userId);

        List<AccountResponse> accountResponses = accounts.stream()
                .map(a -> AccountResponse.from(a, goalLinkedAccountIds.contains(a.getId())))
                .toList();

        BigDecimal totalBalance = accounts.stream()
                .map(Account::getCurrentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<GoalResponse> activeNonUpcomingGoals = activeGoals.stream()
                .filter(g -> !"COMPLETED".equals(g.getStatus()))
                .map(g -> GoalResponse.from(g, goalCreditService.creditedThisMonth(g)))
                .filter(r -> !r.isUpcoming())
                .toList();

        BigDecimal monthlyTarget = activeNonUpcomingGoals.stream()
                .map(GoalResponse::currentMonthlySavingsRequired)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Saved this month
        LocalDate now = AppClock.today();
        BigDecimal grossSavedThisMonth = Optional.ofNullable(transactionRepository
                .sumTransferToGoalByCalendarMonth(
                        userId,
                        now.getYear(),
                        now.getMonthValue(),
                        AppClock.today())).orElse(BigDecimal.ZERO);

        BigDecimal withdrawalsThisMonth = goalAllocationService
                .sumWithdrawalsByCalendarMonth(userId, now.getYear(), now.getMonthValue());

        BigDecimal savedThisMonth = grossSavedThisMonth.subtract(withdrawalsThisMonth);

        // Balance percentage
        BigDecimal totalIncomeThisMonth = transactionRepository
                .sumByUserIdAndTypesAndMonth(
                        userId,
                        List.of(TransactionType.INCOME),
                        now.getYear(),
                        now.getMonthValue());

        BigDecimal openingBalanceThisMonth = transactionRepository
                .sumByUserIdAndTypesAndCategoryAndMonth(
                        userId,
                        List.of(TransactionType.SETTLEMENT),
                        Category.OPENING_BALANCE_CATEGORY_ID,
                        now.getYear(),
                        now.getMonthValue());

        BigDecimal balancePercentageBase = totalIncomeThisMonth.add(openingBalanceThisMonth);

        BigDecimal balancePercentage = balancePercentageBase.compareTo(BigDecimal.ZERO) > 0
                ? totalBalance.multiply(new BigDecimal("100"))
                .divide(balancePercentageBase, 1, RoundingMode.HALF_UP)
                : null;

        // Savings message
        String savingsMessage = buildSavingsMessage(savedThisMonth, monthlyTarget, totalBalance, !activeNonUpcomingGoals.isEmpty());

        // Last entries
        List<Transaction> recentTransactions = transactionRepository
                .findTop5ByUserIdOrderByDateDescCreatedAtDesc(userId);

        Map<String, List<TransactionGoalAllocation>> allocationsByTransactionId = goalAllocationService
                .getAllocationEntitiesByTransactionIds(
                        recentTransactions.stream().map(Transaction::getId).toList());

        List<TransactionResponse> lastEntries = recentTransactions.stream()
                .map(t -> TransactionResponse.from(t, allocationsByTransactionId.getOrDefault(t.getId(), List.of())))
                .toList();

        // Onboarding checklist
        boolean accountsAdded = !accounts.isEmpty();
        boolean goalsAdded = !activeGoals.isEmpty();

        DashboardResponse.OnboardingChecklist checklist = new DashboardResponse.OnboardingChecklist(
                accountsAdded,
                false, // PlannedAmounts deferred to Phase 2
                goalsAdded);

        // Motivational quote
        String quote = MOTIVATIONAL_QUOTES.get(Math.floorMod(userId.hashCode(), MOTIVATIONAL_QUOTES.size()));

        // Assemble response
        DashboardResponse.UserSummary userSummary =
                new DashboardResponse.UserSummary(
                        user.getFirstName(),
                        user.getOnboardingStep(),
                        quote);

        DashboardResponse.BalanceSummary balanceSummary =
                new DashboardResponse.BalanceSummary(
                        totalBalance,
                        balancePercentage,
                        monthlyTarget,
                        savedThisMonth,
                        savingsMessage);

        return new DashboardResponse(
                userSummary,
                balanceSummary,
                accountResponses,
                lastEntries,
                checklist);
    }

    private String buildSavingsMessage(
            BigDecimal savedThisMonth, BigDecimal monthlyTarget, BigDecimal totalBalance, boolean hasActiveGoals) {
        // No goal currently counts toward this month's target - distinct from monthlyTarget
        // legitimately being 0 because an active goal already met its pace this month.
        if (!hasActiveGoals) {
            return "Set a savings goal to get started!";
        }

        BigDecimal remaining = monthlyTarget.subtract(savedThisMonth);

        // Target reached, or close enough that the gap is rounding dust, not a
        // meaningful amount to ask someone to go save today (e.g. a few paise off
        // after netting real transactions).
        if (remaining.compareTo(NEGLIGIBLE_REMAINDER) < 0) {
            return "🎯 You are all set! Just hold on to it 💰";
        }

        // Balance is less than what's needed — encourage partial saving
        if (totalBalance.compareTo(remaining) < 0) {
            return "Save what you can — every ₹"
                    + totalBalance.setScale(0, RoundingMode.FLOOR)
                    + " counts towards your goal! 💪";
        }

        // Balance covers the remaining target — actionable message.
        // CEILING, not FLOOR: rounding down here would understate what's actually
        // needed to hit the exact target (e.g. ₹5000.60 remaining → "save ₹5000"
        // leaves the target 60 paise short even if followed exactly).
        return "You can save ₹" + remaining.setScale(0, RoundingMode.CEILING) + " today!";
    }
}
