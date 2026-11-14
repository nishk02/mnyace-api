package com.mnyace.transaction;

import com.mnyace.account.Account;
import com.mnyace.account.AccountRepository;
import com.mnyace.auth.AuthIntegrationTestBase;
import com.mnyace.auth.User;
import com.mnyace.auth.UserRepository;
import com.mnyace.shared.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CashOverdrawTest extends AuthIntegrationTestBase {
    private static final String PASSWORD = "MemberPass123!";
    private final LocalDate today = AppClock.today();

    @Autowired
    private UserRepository users;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private String auth;
    private User member;
    private String cash;
    private String bank;

    @BeforeEach
    void newMemberWithCashAndBank() {
        String email = "overdraw-" + UUID.randomUUID() + "@test.local";
        User user = new User();
        user.setFirstName("Over");
        user.setLastName("Draw");
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole("MEMBER");
        member = users.save(user);
        cash = accountOf("Cash", Account.CASH_TYPE, "100");
        bank = accountOf("Bank", "BANK", "100");
        auth = "Bearer " + accessTokenOf(signIn(email, PASSWORD, "X-Forwarded-For", freshIp()));
    }

    @Test
    void cash_cannotBeSpentBeyondItsBalance() {
        assertThat(expense(cash, "100").statusCode()).isEqualTo(200);

        HttpResponse<String> refused = expense(cash, "1");

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused).path("error").path("message").asText()).contains("Cash balance below ₹0, by ₹1");
        assertThat(balanceOf(cash)).isEqualByComparingTo("0");
    }

    @Test
    void aBank_mayOverdraw() {
        assertThat(expense(bank, "250").statusCode()).isEqualTo(200);

        assertThat(balanceOf(bank)).isEqualByComparingTo("-150");
    }

    @Test
    void aTransferOutOfCash_cannotOverdrawIt_butOneIntoCashIsFine() {
        HttpResponse<String> refused = transfer(cash, bank, "101");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);

        HttpResponse<String> allowed = transfer(bank, cash, "100");
        assertThat(allowed.statusCode()).as(allowed.body()).isEqualTo(200);
        assertThat(balanceOf(cash)).isEqualByComparingTo("200");
        assertThat(balanceOf(bank)).isEqualByComparingTo("0");
    }

    @Test
    void raisingAnExpenseOnCash_pastItsBalance_isRefused() {
        String id = json(expense(cash, "40")).path("data").path("id").asText();

        HttpResponse<String> refused = put("/api/transactions/" + id, "{\"amount\":150}", "Authorization", auth);

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(balanceOf(cash)).isEqualByComparingTo("60");
    }

    @Test
    void deletingIncomeThatCashAlreadySpent_isRefused() {
        String income = json(post("/api/transactions", transactionJson("INCOME", cash, "50", "cat-03"),
                "Authorization", auth)).path("data").path("id").asText();
        assertThat(expense(cash, "150").statusCode()).isEqualTo(200);

        HttpResponse<String> refused = delete("/api/transactions/" + income, "Authorization", auth);

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(balanceOf(cash)).isEqualByComparingTo("0");
    }

    @Test
    void aGoal_cannotBeLinkedToAnOverdrawnAccount() {
        expense(bank, "250");

        HttpResponse<String> refused = post("/api/goals",
                "{\"name\":\"Trip\",\"targetAmount\":5000,\"accountId\":\"" + bank + "\",\"startDate\":\"" + today
                        + "\",\"endDate\":\"" + today.plusYears(1) + "\"}", "Authorization", auth);

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused).path("error").path("message").asText()).contains("below ₹0");
    }

    private String accountOf(String name, String type, String balance) {
        Account account = new Account();
        account.setUser(member);
        account.setName(name);
        account.setType(type);
        account.setCurrentBalance(new BigDecimal(balance));
        return accounts.save(account).getId();
    }

    private BigDecimal balanceOf(String accountId) {
        return accounts.findById(accountId).orElseThrow().getCurrentBalance();
    }

    private HttpResponse<String> expense(String accountId, String amount) {
        return post("/api/transactions", transactionJson("VARIABLE_EXPENSE", accountId, amount, "cat-07"),
                "Authorization", auth);
    }

    private HttpResponse<String> transfer(String from, String to, String amount) {
        return post("/api/transactions",
                "{\"date\":\"" + today + "\",\"type\":\"TRANSFER\",\"categoryId\":\"cat-01\",\"accountId\":\"" + from
                        + "\",\"toAccountId\":\"" + to + "\",\"amount\":" + amount + "}", "Authorization", auth);
    }

    private String transactionJson(String type, String accountId, String amount, String category) {
        return "{\"date\":\"" + today + "\",\"type\":\"" + type + "\",\"categoryId\":\"" + category
                + "\",\"accountId\":\"" + accountId + "\",\"amount\":" + amount + "}";
    }
}
