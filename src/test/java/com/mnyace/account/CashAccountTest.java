package com.mnyace.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.mnyace.auth.AuthIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CashAccountTest extends AuthIntegrationTestBase {
    private String auth;

    @BeforeEach
    void signInAsAdmin() {
        auth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
    }

    @Test
    void everyUser_hasExactlyOneCashAccountNamedCash() {
        assertThat(cashAccount().path("name").asText()).isEqualTo("Cash");
    }

    @Test
    void aCashAccount_cannotBeCreated() {
        HttpResponse<String> response = post("/api/accounts",
                "{\"name\":\"Pocket\",\"type\":\"CASH\",\"currentBalance\":10}", "Authorization", auth);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("error").path("message").asText()).contains("BANK or WALLET");
    }

    @Test
    void theCashAccountName_isTaken() {
        HttpResponse<String> response = post("/api/accounts",
                "{\"name\":\"cash\",\"type\":\"WALLET\",\"currentBalance\":0}", "Authorization", auth);

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    void theCashBalance_canBeSetToWhatTheUserHolds() {
        String id = cashAccount().path("id").asText();

        HttpResponse<String> response = put("/api/accounts/" + id,
                "{\"type\":\"CASH\",\"currentBalance\":1250.50}", "Authorization", auth);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(cashAccount().path("currentBalance").decimalValue()).isEqualByComparingTo("1250.50");
        put("/api/accounts/" + id, "{\"type\":\"CASH\",\"currentBalance\":0}", "Authorization", auth);
    }

    @Test
    void theCashType_cannotBeChangedAwayFromOrOntoAnotherAccount() {
        String cashId = cashAccount().path("id").asText();
        String bankId = idOf(post("/api/accounts",
                "{\"name\":\"Cash-test-bank\",\"type\":\"BANK\",\"currentBalance\":0}", "Authorization", auth));

        assertThat(put("/api/accounts/" + cashId, "{\"type\":\"BANK\"}", "Authorization", auth).statusCode())
                .isEqualTo(400);
        assertThat(put("/api/accounts/" + bankId, "{\"type\":\"CASH\"}", "Authorization", auth).statusCode())
                .isEqualTo(400);
    }

    @Test
    void theCashAccount_cannotBeDeleted() {
        String id = cashAccount().path("id").asText();

        assertThat(delete("/api/accounts/" + id, "Authorization", auth).statusCode()).isEqualTo(400);
        assertThat(cashAccount().path("id").asText()).isEqualTo(id);
    }

    private JsonNode cashAccount() {
        JsonNode accounts = json(get("/api/accounts", "Authorization", auth)).path("data").path("accounts");
        JsonNode found = null;
        int count = 0;
        for (JsonNode account : accounts) {
            if ("CASH".equals(account.path("type").asText())) {
                found = account;
                count++;
            }
        }
        assertThat(count).isEqualTo(1);
        return found;
    }

    private String idOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).path("data").path("id").asText();
    }
}
