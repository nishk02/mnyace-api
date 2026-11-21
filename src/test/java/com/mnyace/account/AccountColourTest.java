package com.mnyace.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.mnyace.auth.AuthIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountColourTest extends AuthIntegrationTestBase {
    @Autowired
    private JdbcTemplate jdbc;

    private String auth;
    private final List<String> created = new ArrayList<>();

    @BeforeEach
    void signInAsAdmin() {
        auth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("UPDATE banks SET color_light = '#2B7FD1', color_dark = '#2B7FD1' WHERE code = 'SBI'");
        created.forEach(id -> delete("/api/accounts/" + id, "Authorization", auth));
    }

    @Test
    void aListedBank_wearsTheCatalogColours_andHoldsNoKey() {
        JsonNode account = account(idOf(create("{\"type\":\"BANK\",\"bankCode\":\"HDFC\"}")));

        assertThat(account.path("colorKey").isNull()).isTrue();
        assertThat(account.path("colorLight").asText()).isEqualTo(bank("HDFC").path("colorLight").asText());
        assertThat(account.path("colorDark").asText()).isEqualTo(bank("HDFC").path("colorDark").asText());
        assertThat(account.path("colorLabel").isMissingNode()).isTrue();
    }

    @Test
    void aListedBank_refusesAColourKey() {
        HttpResponse<String> response = create("{\"type\":\"BANK\",\"bankCode\":\"HDFC\",\"colorKey\":\"red\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("error").path("message").asText()).contains("colorKey");

        String id = idOf(create("{\"type\":\"BANK\",\"bankCode\":\"HDFC\"}"));
        assertThat(put("/api/accounts/" + id, "{\"colorKey\":\"red\"}", "Authorization", auth).statusCode())
                .isEqualTo(400);
    }

    @Test
    void aWalletAndAnOtherBank_pickOneOfTheTenNames_andCarryNoHex() {
        for (String key : List.of("blue", "teal", "green", "yellow", "orange", "red", "pink", "purple", "deep-purple", "brown")) {
            JsonNode wallet = account(idOf(create("{\"type\":\"WALLET\",\"name\":\"w-" + freshDigits() + "\",\"colorKey\":\"" + key + "\"}")));
            assertThat(wallet.path("colorKey").asText()).isEqualTo(key);
            assertThat(wallet.path("colorLight").isNull()).isTrue();
            assertThat(wallet.path("colorDark").isNull()).isTrue();
        }

        JsonNode other = account(idOf(create("{\"type\":\"BANK\",\"customBank\":true,\"bankCode\":\"LCOOP\","
                + "\"name\":\"Coop " + freshDigits() + "\",\"colorKey\":\"teal\"}")));
        assertThat(other.path("colorKey").asText()).isEqualTo("teal");
        assertThat(other.path("colorLight").isNull()).isTrue();
    }

    @Test
    void aKeyOutsideThePalette_isRejected() {
        assertThat(create("{\"type\":\"WALLET\",\"name\":\"w-" + freshDigits() + "\",\"colorKey\":\"#E8A04D\"}")
                .statusCode()).isEqualTo(400);
        assertThat(create("{\"type\":\"WALLET\",\"name\":\"w-" + freshDigits() + "\",\"colorKey\":\"mauve\"}")
                .statusCode()).isEqualTo(400);
    }

    @Test
    void anAccountWithoutAKey_isGivenTheLeastUsedPaletteColour() {
        JsonNode first = account(idOf(create("{\"type\":\"WALLET\",\"name\":\"w-" + freshDigits() + "\"}")));
        JsonNode second = account(idOf(create("{\"type\":\"WALLET\",\"name\":\"w-" + freshDigits() + "\"}")));

        assertThat(first.path("colorKey").asText()).isNotBlank();
        assertThat(second.path("colorKey").asText()).isNotEqualTo(first.path("colorKey").asText());
    }

    @Test
    void anEdit_keepsTheKeyWhenOmitted_andReplacesItWhenSent() {
        String id = idOf(create("{\"type\":\"WALLET\",\"name\":\"w-" + freshDigits() + "\",\"colorKey\":\"pink\"}"));

        put("/api/accounts/" + id, "{\"currentBalance\":0}", "Authorization", auth);
        assertThat(account(id).path("colorKey").asText()).isEqualTo("pink");

        assertThat(put("/api/accounts/" + id, "{\"colorKey\":\"yellow\"}", "Authorization", auth).statusCode())
                .isEqualTo(200);
        assertThat(account(id).path("colorKey").asText()).isEqualTo("yellow");
        assertThat(put("/api/accounts/" + id, "{\"colorKey\":\"mauve\"}", "Authorization", auth).statusCode())
                .isEqualTo(400);
        assertThat(account(id).path("colorKey").asText()).isEqualTo("yellow");
    }

    @Test
    void movingToAListedBank_dropsTheKey_andMovingBackKeepsNoStaleColour() {
        String id = idOf(create("{\"type\":\"BANK\",\"customBank\":true,\"bankCode\":\"LCOOP\",\"name\":\"Coop "
                + freshDigits() + "\",\"colorKey\":\"red\",\"accountSuffix\":\"" + freshDigits() + "\"}"));

        assertThat(put("/api/accounts/" + id, "{\"bankCode\":\"AXIS\"}", "Authorization", auth).statusCode())
                .isEqualTo(200);
        assertThat(account(id).path("colorKey").isNull()).isTrue();
        assertThat(account(id).path("colorLight").asText()).isEqualTo(bank("AXIS").path("colorLight").asText());
    }

    @Test
    void aCatalogColourChange_reachesAccountsThatAlreadyExist() {
        String id = idOf(create("{\"type\":\"BANK\",\"bankCode\":\"SBI\"}"));

        jdbc.update("UPDATE banks SET color_light = '#112233', color_dark = '#AABBCC' WHERE code = 'SBI'");

        assertThat(account(id).path("colorLight").asText()).isEqualTo("#112233");
        assertThat(account(id).path("colorDark").asText()).isEqualTo("#AABBCC");
    }

    @Test
    void anOtherBank_neverTakesTheColourOfACatalogBankThatSharesItsCodeLater() {
        String id = idOf(create("{\"type\":\"BANK\",\"customBank\":true,\"bankCode\":\"ZZCOL\",\"name\":\"Coop "
                + freshDigits() + "\",\"colorKey\":\"blue\",\"accountSuffix\":\"" + freshDigits() + "\"}"));
        jdbc.update("INSERT INTO banks (code, name, display_order, color_light, color_dark) VALUES ('ZZCOL', 'Late Bank', 999, '#010203', '#040506')");
        try {
            assertThat(account(id).path("colorKey").asText()).isEqualTo("blue");
            assertThat(account(id).path("colorLight").isNull()).isTrue();
        } finally {
            jdbc.update("DELETE FROM banks WHERE code = 'ZZCOL'");
        }
    }

    @Test
    void cash_hasAFixedColour_thatCannotBeChanged() {
        JsonNode cash = cashAccount();

        assertThat(cash.path("colorKey").asText()).isEqualTo("green");
        assertThat(put("/api/accounts/" + cash.path("id").asText(), "{\"colorKey\":\"red\"}", "Authorization", auth)
                .statusCode()).isEqualTo(400);
        // resending the stored colour with a balance is the edit form's normal request
        assertThat(put("/api/accounts/" + cash.path("id").asText(), "{\"colorKey\":\"green\",\"currentBalance\":0}",
                "Authorization", auth).statusCode()).isEqualTo(200);
    }

    @Test
    void aTransactionShowsTheAccountColoursBesideItsName() {
        String id = idOf(create("{\"type\":\"BANK\",\"bankCode\":\"BOI\",\"currentBalance\":100}"));
        String today = java.time.LocalDate.now(com.mnyace.shared.util.AppClock.ZONE).toString();

        HttpResponse<String> income = post("/api/transactions",
                "{\"date\":\"" + today + "\",\"type\":\"INCOME\",\"categoryId\":\"cat-03\",\"accountId\":\"" + id
                        + "\",\"amount\":10}", "Authorization", auth);
        assertThat(income.statusCode()).as(income.body()).isEqualTo(200);
        JsonNode summary = json(income).path("data").path("account");
        assertThat(summary.path("colorLight").asText()).isEqualTo(bank("BOI").path("colorLight").asText());
        assertThat(summary.path("colorDark").asText()).isEqualTo(bank("BOI").path("colorDark").asText());
        assertThat(summary.path("colorKey").isNull()).isTrue();
        delete("/api/transactions/" + json(income).path("data").path("id").asText(), "Authorization", auth);
    }

    private HttpResponse<String> create(String body) {
        String balance = body.contains("currentBalance") ? "" : ",\"currentBalance\":0";
        String digits = body.contains("\"BANK\"") && !body.contains("accountSuffix")
                ? ",\"accountSuffix\":\"" + freshDigits() + "\"" : "";
        return post("/api/accounts", body.substring(0, body.length() - 1) + balance + digits + "}",
                "Authorization", auth);
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

    private JsonNode bank(String code) {
        for (JsonNode bank : json(get("/api/banks", "Authorization", auth)).path("data")) {
            if (code.equals(bank.path("code").asText())) {
                return bank;
            }
        }
        throw new AssertionError("no bank " + code);
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
