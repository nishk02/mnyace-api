package com.mnyace.dashboard;

import com.mnyace.account.Account;
import com.mnyace.account.AccountRepository;
import com.mnyace.auth.User;
import com.mnyace.auth.UserRepository;
import com.mnyace.goal.Goal;
import com.mnyace.goal.GoalCreditService;
import com.mnyace.goal.GoalRepository;
import com.mnyace.shared.util.AppClock;
import com.mnyace.transaction.GoalAllocationService;
import com.mnyace.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardMonthlyTargetTest {
    private static final String USER_ID = "user-1";

    private GoalRepository goals;
    private GoalCreditService credits;
    private TransactionRepository transactions;
    private GoalAllocationService allocations;
    private AccountRepository accounts;
    private DashboardService service;
    private Account account;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        accounts = mock(AccountRepository.class);
        transactions = mock(TransactionRepository.class);
        goals = mock(GoalRepository.class);
        allocations = mock(GoalAllocationService.class);
        credits = mock(GoalCreditService.class);
        service = new DashboardService(users, accounts, transactions, goals, allocations, credits);

        User user = new User();
        user.setFirstName("Pat");
        when(users.findById(USER_ID)).thenReturn(java.util.Optional.of(user));

        account = new Account();
        account.setId("acc-1");
        account.setName("Main");
        account.setCurrentBalance(new BigDecimal("50000"));
        when(accounts.findByUserIdAndActiveTrue(USER_ID)).thenReturn(List.of(account));

        when(transactions.sumByUserIdAndTypesAndMonth(anyString(), any(), anyInt(), anyInt())).thenReturn(BigDecimal.ZERO);
        when(transactions.sumByUserIdAndTypesAndCategoryAndMonth(anyString(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(BigDecimal.ZERO);
        when(allocations.sumWithdrawalsByCalendarMonth(eq(USER_ID), anyInt(), anyInt())).thenReturn(BigDecimal.ZERO);
        saved("0");
    }

    @Test
    void monthlyTargetAddsBackThisMonthsCreditSoItIsSubtractedOnlyOnce() {
        Goal emergency = goal("g1", "300000", "0", "12500", 24, 0);
        Goal vacation = goal("g2", "30000", "10000", "10000", 3, 0);
        when(goals.findByUserIdAndActiveTrueOrderByDisplayOrderAsc(USER_ID)).thenReturn(List.of(emergency, vacation));
        when(credits.creditedThisMonth(emergency)).thenReturn(BigDecimal.ZERO);
        when(credits.creditedThisMonth(vacation)).thenReturn(new BigDecimal("10000"));
        saved("10000");

        DashboardResponse.BalanceSummary balance = service.getDashboard(USER_ID).balance();

        assertThat(balance.monthlyTarget()).isEqualByComparingTo("22500");
        assertThat(balance.savedThisMonth()).isEqualByComparingTo("10000");
        assertThat(balance.savingsMessage()).isEqualTo("You can save ₹12500 today!");
    }

    @Test
    void reportsAllSetOnceTheWholeMonthlyTargetIsSaved() {
        Goal emergency = goal("g1", "300000", "12500", "12500", 24, 0);
        Goal vacation = goal("g2", "30000", "10000", "10000", 3, 0);
        when(goals.findByUserIdAndActiveTrueOrderByDisplayOrderAsc(USER_ID)).thenReturn(List.of(emergency, vacation));
        when(credits.creditedThisMonth(emergency)).thenReturn(new BigDecimal("12500"));
        when(credits.creditedThisMonth(vacation)).thenReturn(new BigDecimal("10000"));
        saved("22500");

        DashboardResponse.BalanceSummary balance = service.getDashboard(USER_ID).balance();

        assertThat(balance.monthlyTarget()).isEqualByComparingTo("22500");
        assertThat(balance.savingsMessage()).isEqualTo("🎯 You are all set! Just hold on to it 💰");
    }

    @Test
    void excludesAnUpcomingGoalFromTheMonthlyTarget() {
        Goal active = goal("g1", "300000", "0", "12500", 24, 0);
        Goal upcoming = goal("g2", "60000", "0", "10000", 6, 2);
        when(goals.findByUserIdAndActiveTrueOrderByDisplayOrderAsc(USER_ID)).thenReturn(List.of(active, upcoming));
        when(credits.creditedThisMonth(any())).thenReturn(BigDecimal.ZERO);

        DashboardResponse.BalanceSummary balance = service.getDashboard(USER_ID).balance();

        assertThat(balance.monthlyTarget()).isEqualByComparingTo("12500");
    }

    private void saved(String amount) {
        when(transactions.sumTransferToGoalByCalendarMonth(eq(USER_ID), anyInt(), anyInt(), any(LocalDate.class)))
                .thenReturn(new BigDecimal(amount));
    }

    /** A goal starting {@code startsInMonths} from now, ending {@code months} months after this month. */
    private Goal goal(String id, String target, String progress, String planned, int months, int startsInMonths) {
        LocalDate thisMonth = AppClock.today().withDayOfMonth(1);
        Goal g = new Goal();
        g.setId(id);
        g.setName(id);
        g.setAccount(account);
        g.setTargetAmount(new BigDecimal(target));
        g.setCurrentProgress(new BigDecimal(progress));
        g.setMonthlySavingsRequired(new BigDecimal(planned));
        g.setStartDate(thisMonth.plusMonths(startsInMonths));
        g.setEndDate(thisMonth.plusMonths(months));
        return g;
    }
}
