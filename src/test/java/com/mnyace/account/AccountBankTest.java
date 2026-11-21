package com.mnyace.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.mnyace.auth.AuthIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountBankTest extends AuthIntegrationTestBase {
    @Autowired
    private JdbcTemplate jdbc;

    private String auth;
    private final List<String> created = new ArrayList<>();
    // a deleted account keeps its name, so each test brings its own
    private final String coop = "Coop " + freshDigits();

    @BeforeEach
    void signInAsAdmin() {
        auth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
    }

    @AfterEach
    void removeWhatWasCreated() {
        jdbc.update("UPDATE banks SET is_active = 1 WHERE code = 'IPPB'");
        jdbc.update("DELETE FROM banks WHERE code = 'ZZTEST'");
        created.forEach(id -> delete("/api/accounts/" + id, "Authorization", auth));
    }

    @Test
    void aListedBank_setsTheNameFromTheCatalog_andIgnoresAnyNameSent() {
        JsonNode account = account(idOf(create("{\"bankCode\":\"BOI\",\"name\":\"Whatever\"}")));

        assertThat(account.path("name").asText()).isEqualTo("Bank of India");
        assertThat(account.path("bankCode").asText()).isEqualTo("BOI");
        assertThat(account.path("customBank").asBoolean()).isFalse();
    }

    @Test
    void aListedBank_needsNoName() {
        HttpResponse<String> response = create("{\"bankCode\":\"HDFC\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).path("data").path("customBank").asBoolean()).isFalse();
        idOf(response);
    }

    @Test
    void twoAccountsAtOneListedBank_areAllowed() {
        String first = idOf(create("{\"bankCode\":\"AXIS\"}"));
        String second = idOf(create("{\"bankCode\":\"AXIS\"}"));

        assertThat(account(first).path("name").asText()).isEqualTo(account(second).path("name").asText());
    }

    @Test
    void anUnknownCode_isRejected() {
        assertThat(create("{\"bankCode\":\"NOPE\"}").statusCode()).isEqualTo(400);
        assertThat(create("{\"bankCode\":\"sbi\"}").statusCode()).isEqualTo(400);
        assertThat(create("{\"bankCode\":\"S\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void anInactiveBank_cannotBePickedAnymore_butStaysOnTheAccountsUsingIt() {
        String id = idOf(create("{\"bankCode\":\"IPPB\"}"));
        jdbc.update("UPDATE banks SET is_active = 0 WHERE code = 'IPPB'");

        assertThat(create("{\"bankCode\":\"IPPB\"}").statusCode()).isEqualTo(400);
        assertThat(account(id).path("bankCode").asText()).isEqualTo("IPPB");
        assertThat(put("/api/accounts/" + id, "{\"bankCode\":\"IPPB\",\"currentBalance\":0}", "Authorization", auth)
                .statusCode()).isEqualTo(200);
    }

    @Test
    void anOtherBank_needsItsCodeAndName_andIsMarkedCustom() {
        assertThat(create("{\"customBank\":true,\"name\":\"" + coop + "\"}").statusCode()).isEqualTo(400);
        assertThat(create("{\"customBank\":true,\"bankCode\":\"LCOOP\"}").statusCode()).isEqualTo(400);
        assertThat(create("{\"customBank\":true,\"bankCode\":\"lcoop\",\"name\":\"" + coop + "\"}").statusCode())
                .isEqualTo(400);

        JsonNode account = account(idOf(create("{\"customBank\":true,\"bankCode\":\"LCOOP\",\"name\":\"" + coop + "\"}")));
        assertThat(account.path("name").asText()).isEqualTo(coop);
        assertThat(account.path("bankCode").asText()).isEqualTo("LCOOP");
        assertThat(account.path("customBank").asBoolean()).isTrue();
    }

    @Test
    void anOtherBank_cannotUseACatalogCode_activeOrNot() {
        HttpResponse<String> response = create("{\"customBank\":true,\"bankCode\":\"SBI\",\"name\":\"My SBI\"}");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("error").path("message").asText()).contains("reserved");

        jdbc.update("UPDATE banks SET is_active = 0 WHERE code = 'IPPB'");
        assertThat(create("{\"customBank\":true,\"bankCode\":\"IPPB\",\"name\":\"My post bank\"}").statusCode())
                .isEqualTo(400);
    }

    @Test
    void aCustomBank_staysCustom_evenWhenItsCodeJoinsTheCatalogLater() {
        String id = idOf(create("{\"customBank\":true,\"bankCode\":\"ZZTEST\",\"name\":\"" + coop + "\"}"));
        jdbc.update("INSERT INTO banks (code, name, display_order, color_light, color_dark) VALUES ('ZZTEST', 'Test Bank', 999, '#010203', '#040506')");

        assertThat(account(id).path("customBank").asBoolean()).isTrue();
        assertThat(account(id).path("name").asText()).isEqualTo(coop);
        // resending the account's own code is not a new claim on the catalog's
        assertThat(put("/api/accounts/" + id, "{\"customBank\":true,\"bankCode\":\"ZZTEST\",\"colorKey\":\"red\"}",
                "Authorization", auth).statusCode()).isEqualTo(200);
    }

    @Test
    void anOtherBanksName_staysUnique() {
        idOf(create("{\"customBank\":true,\"bankCode\":\"LCOOP\",\"name\":\"" + coop + "\"}"));

        assertThat(create("{\"customBank\":true,\"bankCode\":\"LCOP2\",\"name\":\"" + coop + "\"}").statusCode())
                .isEqualTo(409);
    }

    @Test
    void walletAndCash_refuseABank() {
        assertThat(post("/api/accounts", "{\"name\":\"w1\",\"type\":\"WALLET\",\"bankCode\":\"SBI\"}",
                "Authorization", auth).statusCode()).isEqualTo(400);
        assertThat(post("/api/accounts",
                "{\"name\":\"w2\",\"type\":\"WALLET\",\"customBank\":true,\"bankCode\":\"WALLETX\"}",
                "Authorization", auth).statusCode()).isEqualTo(400);

        String cashId = cashAccount().path("id").asText();
        assertThat(put("/api/accounts/" + cashId, "{\"bankCode\":\"SBI\"}", "Authorization", auth).statusCode())
                .isEqualTo(400);
        assertThat(put("/api/accounts/" + cashId, "{\"customBank\":true,\"bankCode\":\"MYCASH\"}", "Authorization", auth)
                .statusCode()).isEqualTo(400);
        // the edit form resends empty fields, which must pass
        assertThat(put("/api/accounts/" + cashId,
                "{\"currentBalance\":0,\"bankCode\":null,\"customBank\":false}", "Authorization", auth).statusCode())
                .isEqualTo(200);

        String walletId = idOf(post("/api/accounts", "{\"name\":\"w3\",\"type\":\"WALLET\",\"currentBalance\":0}",
                "Authorization", auth));
        assertThat(put("/api/accounts/" + walletId, "{\"bankCode\":\"HDFC\"}", "Authorization", auth).statusCode())
                .isEqualTo(400);
        assertThat(account(walletId).path("bankCode").isNull()).isTrue();
        assertThat(account(walletId).path("customBank").asBoolean()).isFalse();
    }

    @Test
    void anEdit_leavesTheBankAlone_andSwitchingABankRenamesTheAccount() {
        String id = idOf(create("{\"bankCode\":\"SBI\"}"));

        put("/api/accounts/" + id, "{\"name\":\"Ignored\"}", "Authorization", auth);
        assertThat(account(id).path("name").asText()).isEqualTo("State Bank of India");
        assertThat(account(id).path("bankCode").asText()).isEqualTo("SBI");

        assertThat(put("/api/accounts/" + id, "{\"bankCode\":\"ICICI\"}", "Authorization", auth).statusCode())
                .isEqualTo(200);
        assertThat(account(id).path("name").asText()).isEqualTo("ICICI Bank");
        assertThat(account(id).path("bankCode").asText()).isEqualTo("ICICI");

        assertThat(put("/api/accounts/" + id, "{\"bankCode\":\"NOPE\"}", "Authorization", auth).statusCode())
                .isEqualTo(400);
        assertThat(account(id).path("bankCode").asText()).isEqualTo("ICICI");
    }

    @Test
    void anAccountCanMoveBetweenAListedAndAnOtherBank() {
        String id = idOf(create("{\"bankCode\":\"SBI\"}"));

        assertThat(put("/api/accounts/" + id, "{\"customBank\":true,\"bankCode\":\"LCOOP\"}", "Authorization", auth)
                .statusCode()).isEqualTo(400);
        assertThat(put("/api/accounts/" + id,
                "{\"customBank\":true,\"bankCode\":\"LCOOP\",\"name\":\"" + coop + "\"}", "Authorization", auth)
                .statusCode()).isEqualTo(200);
        assertThat(account(id).path("customBank").asBoolean()).isTrue();
        assertThat(account(id).path("name").asText()).isEqualTo(coop);

        assertThat(put("/api/accounts/" + id, "{\"bankCode\":\"HDFC\"}", "Authorization", auth).statusCode())
                .isEqualTo(200);
        assertThat(account(id).path("customBank").asBoolean()).isFalse();
        assertThat(account(id).path("name").asText()).isEqualTo("HDFC Bank");
    }

    @Test
    void anOlderAccountWithoutABank_keepsWorking() {
        String id = idOf(create(""));

        assertThat(account(id).path("bankCode").isNull()).isTrue();
        assertThat(account(id).path("customBank").asBoolean()).isFalse();
        assertThat(put("/api/accounts/" + id, "{\"colorKey\":\"blue\",\"name\":\"Renamed " + freshDigits() + "\"}",
                "Authorization", auth).statusCode()).isEqualTo(200);
    }

    @Test
    void createdAt_isPresentEverywhere_andNeverChangedByAnEdit() {
        HttpResponse<String> response = create("{\"bankCode\":\"KOTAK\"}");
        String id = idOf(response);
        String createdAt = json(response).path("data").path("createdAt").asText();

        assertThat(Instant.parse(createdAt)).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        assertThat(account(id).path("createdAt").asText()).isEqualTo(createdAt);
        assertThat(listed(id).path("createdAt").asText()).isEqualTo(createdAt);

        HttpResponse<String> updated = put("/api/accounts/" + id,
                "{\"currentBalance\":50,\"createdAt\":\"2001-01-01T00:00:00Z\"}",
                "Authorization", auth);
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(json(updated).path("data").path("createdAt").asText()).isEqualTo(createdAt);
        assertThat(account(id).path("createdAt").asText()).isEqualTo(createdAt);
    }

    @Test
    void aTransactionAndAGoal_showTheBankAndDigitsBesideTheAccountName() {
        String bank = idOf(create("{\"bankCode\":\"BOI\",\"accountSuffix\":\"2864\",\"currentBalance\":1000}"));
        String today = java.time.LocalDate.now(com.mnyace.shared.util.AppClock.ZONE).toString();

        HttpResponse<String> goal = post("/api/goals",
                "{\"name\":\"bank-goal\",\"targetAmount\":5000,\"accountId\":\"" + bank + "\",\"startDate\":\"" + today
                        + "\",\"endDate\":\"" + java.time.LocalDate.parse(today).plusYears(1) + "\"}",
                "Authorization", auth);
        assertThat(goal.statusCode()).as(goal.body()).isIn(200, 201);
        assertThat(json(goal).path("data").path("account").path("bankCode").asText()).isEqualTo("BOI");
        assertThat(json(goal).path("data").path("account").path("accountSuffix").asText()).isEqualTo("2864");
        String goalId = json(goal).path("data").path("id").asText();

        HttpResponse<String> expense = post("/api/transactions",
                "{\"date\":\"" + today + "\",\"type\":\"VARIABLE_EXPENSE\",\"categoryId\":\"cat-12\",\"accountId\":\""
                        + bank + "\",\"amount\":1}", "Authorization", auth);
        // the account backs a goal, so a plain expense is refused; an income row shows the same summary
        HttpResponse<String> income = post("/api/transactions",
                "{\"date\":\"" + today + "\",\"type\":\"INCOME\",\"categoryId\":\"cat-03\",\"accountId\":\"" + bank
                        + "\",\"amount\":10}", "Authorization", auth);
        assertThat(income.statusCode()).as(income.body()).isEqualTo(200);
        JsonNode summary = json(income).path("data").path("account");
        assertThat(summary.path("bankCode").asText()).isEqualTo("BOI");
        assertThat(summary.path("accountSuffix").asText()).isEqualTo("2864");

        String transactionId = json(income).path("data").path("id").asText();
        delete("/api/transactions/" + transactionId, "Authorization", auth);
        if (expense.statusCode() == 200) {
            delete("/api/transactions/" + json(expense).path("data").path("id").asText(), "Authorization", auth);
        }
        delete("/api/goals/" + goalId, "Authorization", auth);
    }

    private HttpResponse<String> create(String extra) {
        // a BANK account must carry digits, so give it some unless the test supplies its own; a name too, unless
        // the request leaves it to a bank
        String body = extra.isEmpty() ? "" : extra.substring(1, extra.length() - 1);
        StringBuilder json = new StringBuilder("{\"type\":\"BANK\"");
        if (!body.contains("accountSuffix")) {
            json.append(",\"accountSuffix\":\"").append(freshDigits()).append('"');
        }
        if (!body.contains("currentBalance")) {
            json.append(",\"currentBalance\":0");
        }
        if (!body.contains("bankCode") && !body.contains("\"name\"")) {
            json.append(",\"name\":\"t-").append(freshDigits()).append('"');
        }
        if (!body.isEmpty()) {
            json.append(',').append(body);
        }
        return post("/api/accounts", json.append('}').toString(), "Authorization", auth);
    }

    private String idOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        String id = json(response).path("data").path("id").asText();
        created.add(id);
        return id;
    }

    private JsonNode account(String id) {
        return json(get("/api/accounts/" + id, "Authorization", auth)).path("data");
    }

    private JsonNode listed(String id) {
        for (JsonNode account : json(get("/api/accounts", "Authorization", auth)).path("data").path("accounts")) {
            if (id.equals(account.path("id").asText())) {
                return account;
            }
        }
        throw new AssertionError("account not listed");
    }

    private JsonNode cashAccount() {
        for (JsonNode account : json(get("/api/accounts", "Authorization", auth)).path("data").path("accounts")) {
            if ("CASH".equals(account.path("type").asText())) {
                return account;
            }
        }
        throw new AssertionError("no Cash account");
    }
}
