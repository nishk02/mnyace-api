package com.mnyace.shared;

import com.mnyace.account.Account;
import com.mnyace.account.AccountRepository;
import com.mnyace.auth.AuthIntegrationTestBase;
import com.mnyace.auth.User;
import com.mnyace.auth.UserRepository;
import com.mnyace.category.CategoryRepository;
import com.mnyace.transaction.Transaction;
import com.mnyace.transaction.TransactionRepository;
import com.mnyace.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// Money is stored as REAL (a binary float) in SQLite. These tests check whether that ever shows.
class MoneyPrecisionTest extends AuthIntegrationTestBase {
    private static final LocalDate DAY = LocalDate.of(2026, 3, 15);

    @Autowired
    private UserRepository users;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private TransactionRepository transactions;

    @Test
    void storedAmounts_comeBackExactlyAsStored() {
        Account account = newAccount();
        List<Transaction> saved = transactions.saveAll(randomIncome(account, 2_000, new Random(7), DAY.minusDays(1)));

        long mismatches = 0;
        BigDecimal worst = BigDecimal.ZERO;
        for (Transaction original : saved) {
            BigDecimal read = transactions.findById(original.getId()).orElseThrow().getAmount();
            if (read.compareTo(original.getAmount()) != 0) {
                mismatches++;
                worst = worst.max(read.subtract(original.getAmount()).abs());
            }
        }

        System.out.println("PRECISION round-trip mismatches: " + mismatches + " of " + saved.size() + ", worst error " + worst);
        assertThat(mismatches).isZero();
    }

    @Test
    void sumOfManyAmounts_isExactOnceRoundedToTwoDecimals() {
        Account account = newAccount();
        List<Transaction> saved = transactions.saveAll(randomIncome(account, 5_000, new Random(42), DAY));
        BigDecimal exact = saved.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal fromDatabase = transactions.sumByUserIdAndTypesAndDateRange(
                adminId(), List.of(TransactionType.INCOME), DAY, DAY);

        System.out.println("PRECISION sum exact=" + exact + " database=" + fromDatabase
                + " difference=" + fromDatabase.subtract(exact));
        assertThat(fromDatabase.setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo(exact);
    }

    @Test
    void api_reportsTenPaisePlusTwentyPaiseAsExactlyThirtyPaise() {
        String auth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        String today = com.mnyace.shared.util.AppClock.today().toString();
        String accountId = json(post("/api/accounts", "{\"name\":\"Pennies\",\"type\":\"BANK\",\"currentBalance\":0}",
                "Authorization", auth)).path("data").path("id").asText();
        for (String amount : List.of("0.10", "0.20")) {
            post("/api/transactions", "{\"date\":\"" + today + "\",\"type\":\"INCOME\",\"categoryId\":\"cat-03\","
                    + "\"accountId\":\"" + accountId + "\",\"amount\":" + amount + "}", "Authorization", auth);
        }

        HttpResponse<String> summary = get("/api/analytics/cashflow-summary?from=" + today + "&to=" + today,
                "Authorization", auth);

        Matcher income = Pattern.compile("\"income\":([0-9.eE+-]+)").matcher(summary.body());
        assertThat(income.find()).as(summary.body()).isTrue();
        System.out.println("PRECISION api income value as sent to clients: " + income.group(1));
        assertThat(new BigDecimal(income.group(1))).isEqualByComparingTo("0.30");
        assertThat(income.group(1)).as("the value must not carry float noise").doesNotContain("0000000");
    }

    @Test
    void api_rejectsAmountsWithMoreThanTwoDecimals() {
        String auth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        String accountId = json(post("/api/accounts", "{\"name\":\"Strict\",\"type\":\"BANK\",\"currentBalance\":0}",
                "Authorization", auth)).path("data").path("id").asText();
        String today = com.mnyace.shared.util.AppClock.today().toString();

        HttpResponse<String> response = post("/api/transactions", "{\"date\":\"" + today
                + "\",\"type\":\"INCOME\",\"categoryId\":\"cat-03\",\"accountId\":\"" + accountId
                + "\",\"amount\":12.345}", "Authorization", auth);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    private String adminId() {
        return users.findByEmailIgnoreCase(ADMIN_EMAIL).map(User::getId).orElseThrow();
    }

    private Account newAccount() {
        Account account = new Account();
        account.setUser(users.findByEmailIgnoreCase(ADMIN_EMAIL).orElseThrow());
        account.setName("Precision " + java.util.UUID.randomUUID());
        account.setType("BANK");
        account.setCurrentBalance(BigDecimal.ZERO);
        return accounts.save(account);
    }

    private List<Transaction> randomIncome(Account account, int count, Random random, LocalDate day) {
        List<Transaction> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Transaction t = new Transaction();
            t.setUser(account.getUser());
            t.setAccount(account);
            t.setCategory(categories.findById("cat-03").orElseThrow());
            t.setType(TransactionType.INCOME);
            t.setDate(day);
            t.setAmount(BigDecimal.valueOf(1 + random.nextInt(9_999_999), 2)); // 0.01 to 99,999.99
            t.setFinancialYear("FY25-26");
            t.setMonth(12);
            t.setCalendarMonth(day.getMonthValue());
            t.setCalendarYear(day.getYear());
            rows.add(t);
        }
        return rows;
    }
}
