package com.moneyflow.shared;

import com.moneyflow.auth.AuthIntegrationTestBase;
import com.moneyflow.shared.email.EmailService;
import com.moneyflow.shared.util.AppClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

// User A (the bootstrap admin) owns the data; user B is a second member who must never reach it.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnershipTest extends AuthIntegrationTestBase {
    private static final String B_EMAIL = "member-b@test.local";
    private static final String B_PASSWORD = "MemberPass123!";
    private static final String MARKER = "owned-by-a-" + UUID.randomUUID();

    @MockitoBean
    private EmailService emailService;

    private String authA;
    private String authB;
    private String accountA;
    private String goalA;
    private String transactionA;
    private boolean ready;
    private final LocalDate today = AppClock.today();

    private void setUpUsersAndData() {
        if (ready) {
            return;
        }
        if (authB == null) {
            authA = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
            authB = "Bearer " + registerMemberB();
        }

        accountA = idOf(post("/api/accounts",
                "{\"name\":\"" + MARKER + "-account\",\"type\":\"BANK\",\"currentBalance\":1000}", "Authorization", authA));
        goalA = idOf(post("/api/goals",
                "{\"name\":\"" + MARKER + "-goal\",\"targetAmount\":5000,\"accountId\":\"" + accountA
                        + "\",\"startDate\":\"" + today + "\",\"endDate\":\"" + today.plusYears(1) + "\"}", "Authorization", authA));
        transactionA = idOf(post("/api/transactions",
                "{\"date\":\"" + today + "\",\"type\":\"INCOME\",\"categoryId\":\"cat-03\",\"accountId\":\"" + accountA
                        + "\",\"amount\":700,\"notes\":\"" + MARKER + "-note\"}", "Authorization", authA));
        ready = true;
    }

    @Test
    void account_otherUserCannotReadChangeOrDelete() {
        setUpUsersAndData();

        assertThat(get("/api/accounts/" + accountA, "Authorization", authB).statusCode()).isEqualTo(404);
        assertThat(put("/api/accounts/" + accountA, "{\"name\":\"hacked\"}", "Authorization", authB).statusCode())
                .isEqualTo(404);
        assertThat(delete("/api/accounts/" + accountA, "Authorization", authB).statusCode()).isEqualTo(404);

        HttpResponse<String> stillThere = get("/api/accounts/" + accountA, "Authorization", authA);
        assertThat(stillThere.statusCode()).isEqualTo(200);
        assertThat(json(stillThere).path("data").path("name").asText()).isEqualTo(MARKER + "-account");
    }

    @Test
    void goal_otherUserCannotReadChangeDeleteOrCompleteIt() {
        setUpUsersAndData();

        assertThat(get("/api/goals/" + goalA, "Authorization", authB).statusCode()).isEqualTo(404);
        assertThat(put("/api/goals/" + goalA, "{\"name\":\"hacked\"}", "Authorization", authB).statusCode())
                .isEqualTo(404);
        assertThat(delete("/api/goals/" + goalA, "Authorization", authB).statusCode()).isEqualTo(404);
        assertThat(post("/api/goals/" + goalA + "/complete", "{}", "Authorization", authB).statusCode())
                .isEqualTo(404);
        assertThat(post("/api/goals/" + goalA + "/reopen", "{}", "Authorization", authB).statusCode())
                .isEqualTo(404);

        HttpResponse<String> stillThere = get("/api/goals/" + goalA, "Authorization", authA);
        assertThat(stillThere.statusCode()).isEqualTo(200);
        assertThat(json(stillThere).path("data").path("name").asText()).isEqualTo(MARKER + "-goal");
    }

    @Test
    void goal_otherUserCannotAttachItToSomeoneElsesAccount() {
        setUpUsersAndData();

        HttpResponse<String> response = post("/api/goals",
                "{\"name\":\"sneaky\",\"targetAmount\":100,\"accountId\":\"" + accountA
                        + "\",\"startDate\":\"" + today + "\",\"endDate\":\"" + today.plusYears(1) + "\"}",
                "Authorization", authB);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void goalReorder_ignoresGoalsOfOtherUsers() {
        setUpUsersAndData();
        int before = json(get("/api/goals/" + goalA, "Authorization", authA)).path("data").path("displayOrder").asInt();

        put("/api/goals/reorder", "{\"items\":[{\"id\":\"" + goalA + "\",\"displayOrder\":99}]}",
                "Authorization", authB);

        int after = json(get("/api/goals/" + goalA, "Authorization", authA)).path("data").path("displayOrder").asInt();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void transaction_otherUserCannotReadChangeOrDeleteIt() {
        setUpUsersAndData();

        assertThat(get("/api/transactions/" + transactionA, "Authorization", authB).statusCode()).isEqualTo(404);
        assertThat(put("/api/transactions/" + transactionA, "{\"notes\":\"hacked\"}", "Authorization", authB)
                .statusCode()).isEqualTo(404);
        assertThat(delete("/api/transactions/" + transactionA + "?confirm=true", "Authorization", authB)
                .statusCode()).isEqualTo(404);

        HttpResponse<String> stillThere = get("/api/transactions/" + transactionA, "Authorization", authA);
        assertThat(stillThere.statusCode()).isEqualTo(200);
        assertThat(json(stillThere).path("data").path("notes").asText()).isEqualTo(MARKER + "-note");
    }

    @Test
    void transaction_otherUserCannotBookAgainstSomeoneElsesAccountOrGoal() {
        setUpUsersAndData();
        String accountB = idOf(post("/api/accounts",
                "{\"name\":\"b-account\",\"type\":\"BANK\",\"currentBalance\":500}", "Authorization", authB));

        assertThat(post("/api/transactions", expense(accountA, null, null), "Authorization", authB).statusCode())
                .isEqualTo(404);
        assertThat(post("/api/transactions", transfer(accountB, accountA, null), "Authorization", authB).statusCode())
                .isEqualTo(404);
        assertThat(post("/api/transactions", transfer(accountB, null, goalA), "Authorization", authB).statusCode())
                .isEqualTo(404);
    }

    @Test
    void listsAndSummaries_neverContainAnotherUsersData() {
        setUpUsersAndData();

        for (String path : List.of(
                "/api/accounts", "/api/goals", "/api/transactions", "/api/dashboard")) {
            HttpResponse<String> response = get(path, "Authorization", authB);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path + " must not leak user A's data").doesNotContain(MARKER);
        }

        HttpResponse<String> analytics = get(range(),
                "Authorization", authB);
        assertThat(analytics.statusCode()).isEqualTo(200);
        assertThat(json(analytics).path("data").path("income").decimalValue()).isEqualByComparingTo("0");

        HttpResponse<String> ownerView = get(range(),
                "Authorization", authA);
        assertThat(json(ownerView).path("data").path("income").decimalValue()).isEqualByComparingTo("700");
    }

    private String range() {
        return "/api/analytics/cashflow-summary?from=" + today.minusDays(1) + "&to=" + today.plusDays(1);
    }

    private String expense(String accountId, String toAccountId, String toGoalId) {
        return "{\"date\":\"" + today + "\",\"type\":\"VARIABLE_EXPENSE\",\"categoryId\":\"cat-03\",\"accountId\":\""
                + accountId + "\",\"amount\":10}";
    }

    private String transfer(String accountId, String toAccountId, String toGoalId) {
        String target = toAccountId != null ? "\"toAccountId\":\"" + toAccountId + "\""
                : "\"toGoalId\":\"" + toGoalId + "\"";
        return "{\"date\":\"" + today + "\",\"type\":\"TRANSFER\",\"categoryId\":\"cat-03\",\"accountId\":\""
                + accountId + "\"," + target + ",\"amount\":10}";
    }

    private String idOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
        return json(response).path("data").path("id").asText();
    }

    private String registerMemberB() {
        String inviteToken = json(post("/api/admin/invites", "{\"email\":\"" + B_EMAIL + "\"}",
                "Authorization", authA)).path("data").path("token").asText();
        post("/auth/invites/" + inviteToken + "/signup",
                "{\"firstName\":\"B\",\"lastName\":\"Member\",\"password\":\"" + B_PASSWORD
                        + "\",\"confirmPassword\":\"" + B_PASSWORD + "\"}", "X-Forwarded-For", freshIp());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendOtpEmail(eq(B_EMAIL), code.capture());
        HttpResponse<String> verified = post("/auth/invites/" + inviteToken + "/verify-otp",
                "{\"code\":\"" + code.getValue() + "\"}", "X-Forwarded-For", freshIp());
        return accessTokenOf(verified);
    }
}
