package com.mnyace.auth;

import com.mnyace.goal.Goal;
import com.mnyace.goal.GoalRepository;
import com.mnyace.invite.Invite;
import com.mnyace.invite.InviteRepository;
import com.mnyace.invite.InviteStatus;
import com.mnyace.shared.util.AppClock;
import com.mnyace.transaction.Transaction;
import com.mnyace.transaction.TransactionGoalAllocation;
import com.mnyace.transaction.TransactionGoalAllocationRepository;
import com.mnyace.transaction.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountDeletionTest extends AuthIntegrationTestBase {
    private static final String PASSWORD = "MemberPass123!";
    private final LocalDate today = AppClock.today();

    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TransactionRepository transactions;
    @Autowired
    private GoalRepository goals;
    @Autowired
    private TransactionGoalAllocationRepository allocations;
    @Autowired
    private PasswordResetRepository passwordResets;
    @Autowired
    private InviteRepository invites;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void deletingAUser_leavesNoRowForThemInAnyTable_andSparesEveryoneElse() {
        String email = newMember();
        String otherEmail = newMember();
        HttpResponse<String> phone = signIn(email, PASSWORD, "X-Forwarded-For", freshIp());
        HttpResponse<String> laptop = signIn(email, PASSWORD, "X-Forwarded-For", freshIp());
        HttpResponse<String> other = signIn(otherEmail, PASSWORD, "X-Forwarded-For", freshIp());
        String auth = "Bearer " + accessTokenOf(phone);
        String userId = users.findByEmailIgnoreCase(email).orElseThrow().getId();
        String otherId = users.findByEmailIgnoreCase(otherEmail).orElseThrow().getId();

        List<String> transactionIds = seedEverything(email, auth);
        seedAccountAndGoal("Bearer " + accessTokenOf(other));
        assertThat(rowsHeldFor(userId, email, transactionIds)).isNotEmpty();

        HttpResponse<String> deleted = deleteWithBody("/api/users/me", "{\"password\":\"" + PASSWORD + "\"}",
                "Authorization", auth);

        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(rowsHeldFor(userId, email, transactionIds)).isEmpty();
        assertThat(get("/api/accounts", "Authorization", auth).statusCode()).isEqualTo(401);
        assertThat(get("/api/accounts", "Authorization", "Bearer " + accessTokenOf(laptop)).statusCode()).isEqualTo(401);
        assertThat(refresh(refreshTokenOf(laptop), "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(401);
        assertThat(signIn(email, PASSWORD, "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(401);

        assertThat(rowsHeldFor(otherId, otherEmail, List.of())).isNotEmpty();
        assertThat(get("/api/accounts", "Authorization", "Bearer " + accessTokenOf(other)).statusCode()).isEqualTo(200);
    }

    @Test
    void aWrongPassword_deletesNothing() {
        String email = newMember();
        String auth = "Bearer " + accessTokenOf(signIn(email, PASSWORD, "X-Forwarded-For", freshIp()));

        HttpResponse<String> response = deleteWithBody("/api/users/me", "{\"password\":\"nope-nope-nope\"}",
                "Authorization", auth);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("error").path("code").asText()).isEqualTo("INVALID_CURRENT_PASSWORD");
        assertThat(users.findByEmailIgnoreCase(email)).isPresent();
        assertThat(get("/api/accounts", "Authorization", auth).statusCode()).isEqualTo(200);
    }

    @Test
    void anAdminAccount_cannotBeDeleted() {
        HttpResponse<String> admin = signInAdmin("X-Forwarded-For", freshIp());

        HttpResponse<String> response = deleteWithBody("/api/users/me", "{\"password\":\"" + ADMIN_PASSWORD + "\"}",
                "Authorization", "Bearer " + accessTokenOf(admin));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).path("error").path("code").asText()).isEqualTo("ADMIN_ACCOUNT_PROTECTED");
        assertThat(users.findByEmailIgnoreCase(ADMIN_EMAIL)).isPresent();
    }

    @Test
    void itNeedsASignedInUserAndAPassword() {
        assertThat(deleteWithBody("/api/users/me", "{\"password\":\"x\"}").statusCode()).isEqualTo(401);

        String auth = "Bearer " + accessTokenOf(signIn(newMember(), PASSWORD, "X-Forwarded-For", freshIp()));
        assertThat(deleteWithBody("/api/users/me", "{}", "Authorization", auth).statusCode()).isEqualTo(400);
    }

    @Test
    void webClients_getTheirCookieCleared() {
        String email = newMember();
        HttpResponse<String> signedIn = signIn(email, PASSWORD, "X-Forwarded-For", freshIp(), "X-Client-Platform", "web");

        HttpResponse<String> response = deleteWithBody("/api/users/me", "{\"password\":\"" + PASSWORD + "\"}",
                "Authorization", "Bearer " + accessTokenOf(signedIn), "X-Client-Platform", "web");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=0");
    }

    /** One of every kind of data a user can own; returns the ids of their transactions. */
    private List<String> seedEverything(String email, String auth) {
        String bank = idOf(post("/api/accounts",
                "{\"name\":\"bank\",\"type\":\"BANK\",\"currentBalance\":1000}", "Authorization", auth));
        String cash = idOf(post("/api/accounts",
                "{\"name\":\"wallet\",\"type\":\"WALLET\",\"currentBalance\":50}", "Authorization", auth));
        String goal = idOf(post("/api/goals",
                "{\"name\":\"goal\",\"targetAmount\":5000,\"accountId\":\"" + bank + "\",\"startDate\":\"" + today
                        + "\",\"endDate\":\"" + today.plusYears(1) + "\"}", "Authorization", auth));
        String income = idOf(post("/api/transactions",
                "{\"date\":\"" + today + "\",\"type\":\"INCOME\",\"categoryId\":\"cat-03\",\"accountId\":\"" + bank
                        + "\",\"amount\":700,\"notes\":\"salary\"}", "Authorization", auth));
        String expense = idOf(post("/api/transactions",
                "{\"date\":\"" + today + "\",\"type\":\"VARIABLE_EXPENSE\",\"categoryId\":\"cat-01\",\"accountId\":\"" + cash
                        + "\",\"amount\":20}", "Authorization", auth));

        TransactionGoalAllocation allocation = new TransactionGoalAllocation();
        Transaction transaction = transactions.findById(income).orElseThrow();
        Goal goalRow = goals.findById(goal).orElseThrow();
        allocation.setTransaction(transaction);
        allocation.setGoal(goalRow);
        allocation.setAmount(new BigDecimal("100"));
        allocations.save(allocation);

        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        PasswordReset reset = new PasswordReset();
        reset.setUser(user);
        reset.setCodeHash("hash");
        reset.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        reset.setCreatedAt(LocalDateTime.now());
        passwordResets.save(reset);

        User admin = users.findByEmailIgnoreCase(ADMIN_EMAIL).orElseThrow();
        for (String address : List.of(email, email.toUpperCase())) {
            Invite invite = new Invite();
            invite.setEmail(address);
            invite.setToken(UUID.randomUUID().toString());
            invite.setStatus(InviteStatus.COMPLETED);
            invite.setInvitedBy(admin);
            invite.setExpiresAt(LocalDateTime.now().plusDays(7));
            invites.save(invite);
        }
        return List.of(income, expense);
    }

    private void seedAccountAndGoal(String auth) {
        idOf(post("/api/accounts", "{\"name\":\"keep\",\"type\":\"BANK\",\"currentBalance\":10}", "Authorization", auth));
    }

    /** Scans every table, so a table added later that holds user data without being cleared fails this test. */
    private List<String> rowsHeldFor(String userId, String email, List<String> transactionIds) {
        List<String> found = new ArrayList<>();
        for (String table : jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history'",
                String.class)) {
            List<String> columns = jdbc.queryForList("SELECT name FROM pragma_table_info('" + table + "')", String.class);
            for (String column : List.of("user_id", "invited_by")) {
                if (columns.contains(column) && count(table, column + " = ?", userId) > 0) {
                    found.add(table + "." + column);
                }
            }
            if (columns.contains("email") && count(table, "lower(email) = lower(?)", email) > 0) {
                found.add(table + ".email");
            }
            if (columns.contains("transaction_id")) {
                for (String id : transactionIds) {
                    if (count(table, "transaction_id = ?", id) > 0) {
                        found.add(table + ".transaction_id");
                    }
                }
            }
        }
        return found;
    }

    private int count(String table, String where, String value) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, value);
        return count == null ? 0 : count;
    }

    private String idOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
        return json(response).path("data").path("id").asText();
    }

    private String newMember() {
        String email = "delete-" + UUID.randomUUID() + "@test.local";
        User user = new User();
        user.setFirstName("Delete");
        user.setLastName("Tester");
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole("MEMBER");
        users.save(user);
        return email;
    }
}
