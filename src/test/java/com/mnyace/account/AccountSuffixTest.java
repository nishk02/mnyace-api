package com.mnyace.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.mnyace.auth.AuthIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountSuffixTest extends AuthIntegrationTestBase {
    private String auth;
    private final List<String> created = new ArrayList<>();

    @BeforeEach
    void signInAsAdmin() {
        auth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
    }

    @AfterEach
    void removeWhatWasCreated() {
        created.forEach(id -> delete("/api/accounts/" + id, "Authorization", auth));
    }

    @Test
    void anAccountWithoutDigits_returnsNullAndAnEmptyList() {
        String id = bank("");

        JsonNode account = account(id);
        assertThat(account.path("accountSuffix").isNull()).isTrue();
        assertThat(account.path("cardSuffixes").isArray()).isTrue();
        assertThat(account.path("cardSuffixes")).isEmpty();
    }

    @Test
    void theAccountSuffix_mustBeThreeOrFourDigits() {
        assertThat(create("BANK", ",\"accountSuffix\":\"12\"").statusCode()).isEqualTo(400);
        assertThat(create("BANK", ",\"accountSuffix\":\"12345\"").statusCode()).isEqualTo(400);
        assertThat(create("BANK", ",\"accountSuffix\":\"12a4\"").statusCode()).isEqualTo(400);

        assertThat(account(idOf(create("BANK", ",\"accountSuffix\":\"123\""))).path("accountSuffix").asText())
                .isEqualTo("123");
        assertThat(idOf(create("BANK", ",\"accountSuffix\":\"4567\""))).isNotBlank();
    }

    @Test
    void cardSuffixes_areFourDigitsEach_withNoRepeatsAndAtMostFive() {
        assertThat(create("BANK", ",\"cardSuffixes\":[\"123\"]").statusCode()).isEqualTo(400);
        assertThat(create("BANK", ",\"cardSuffixes\":[\"12345\"]").statusCode()).isEqualTo(400);
        assertThat(create("BANK", ",\"cardSuffixes\":[\"1111\",\"1111\"]").statusCode()).isEqualTo(400);
        assertThat(create("BANK", ",\"cardSuffixes\":[\"1001\",\"1002\",\"1003\",\"1004\",\"1005\",\"1006\"]").statusCode())
                .isEqualTo(400);

        String id = idOf(create("BANK", ",\"cardSuffixes\":[\"1001\",\"1002\",\"1003\",\"1004\",\"1005\"]"));
        assertThat(account(id).path("cardSuffixes")).hasSize(5);
    }

    @Test
    void walletAndCash_refuseDigits() {
        assertThat(create("WALLET", ",\"accountSuffix\":\"1234\"").statusCode()).isEqualTo(400);
        assertThat(create("WALLET", ",\"cardSuffixes\":[\"1234\"]").statusCode()).isEqualTo(400);

        JsonNode cash = cashAccount();
        String cashId = cash.path("id").asText();
        assertThat(put("/api/accounts/" + cashId, "{\"accountSuffix\":\"1234\"}", "Authorization", auth).statusCode())
                .isEqualTo(400);
        assertThat(put("/api/accounts/" + cashId, "{\"cardSuffixes\":[\"1234\"]}", "Authorization", auth).statusCode())
                .isEqualTo(400);
        // the edit form resends every field, so empty digits must pass
        assertThat(put("/api/accounts/" + cashId,
                "{\"currentBalance\":0,\"accountSuffix\":null,\"cardSuffixes\":[]}", "Authorization", auth).statusCode())
                .isEqualTo(200);
    }

    @Test
    void digitsAlreadyOnAnotherActiveAccount_areRefused() {
        idOf(create("BANK", ",\"accountSuffix\":\"3388\",\"cardSuffixes\":[\"8155\"]"));

        assertThat(create("BANK", ",\"accountSuffix\":\"3388\"").statusCode()).isEqualTo(400);
        // 388 is the tail of 3388, so an SMS ending 388 could mean either account
        assertThat(create("BANK", ",\"accountSuffix\":\"388\"").statusCode()).isEqualTo(400);
        assertThat(create("BANK", ",\"cardSuffixes\":[\"8155\"]").statusCode()).isEqualTo(400);
        assertThat(create("BANK", ",\"accountSuffix\":\"3389\",\"cardSuffixes\":[\"8156\"]").statusCode()).isEqualTo(201);
    }

    @Test
    void aDeletedAccountsDigits_canBeUsedAgain() {
        String first = idOf(create("BANK", ",\"accountSuffix\":\"5566\",\"cardSuffixes\":[\"7788\"]"));
        assertThat(delete("/api/accounts/" + first, "Authorization", auth).statusCode()).isEqualTo(200);

        assertThat(create("BANK", ",\"accountSuffix\":\"5566\",\"cardSuffixes\":[\"7788\"]").statusCode()).isEqualTo(201);
    }

    @Test
    void anEdit_leavesOmittedFieldsAlone_clearsOnNull_andReplacesTheCardList() {
        String id = idOf(create("BANK", ",\"accountSuffix\":\"2468\",\"cardSuffixes\":[\"1357\"]"));

        put("/api/accounts/" + id, "{\"colorLabel\":\"blue\"}", "Authorization", auth);
        assertThat(account(id).path("accountSuffix").asText()).isEqualTo("2468");
        assertThat(account(id).path("cardSuffixes")).hasSize(1);

        put("/api/accounts/" + id, "{\"accountSuffix\":null}", "Authorization", auth);
        assertThat(account(id).path("accountSuffix").isNull()).isTrue();
        assertThat(account(id).path("cardSuffixes")).hasSize(1);

        put("/api/accounts/" + id, "{\"cardSuffixes\":[\"1111\",\"2222\"]}", "Authorization", auth);
        assertThat(account(id).path("cardSuffixes")).hasSize(2);

        put("/api/accounts/" + id, "{\"cardSuffixes\":[]}", "Authorization", auth);
        assertThat(account(id).path("cardSuffixes")).isEmpty();
    }

    @Test
    void aBalanceCorrection_works_alongsideDigits_andARefusedEditChangesNothing() {
        String other = idOf(create("BANK", ",\"accountSuffix\":\"9001\""));
        String id = idOf(create("BANK", ",\"currentBalance\":100"));

        HttpResponse<String> response = put("/api/accounts/" + id,
                "{\"currentBalance\":60,\"accountSuffix\":\"9002\"}", "Authorization", auth);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(account(id).path("currentBalance").decimalValue()).isEqualByComparingTo("60");
        assertThat(account(id).path("accountSuffix").asText()).isEqualTo("9002");

        HttpResponse<String> refused = put("/api/accounts/" + id,
                "{\"currentBalance\":10,\"accountSuffix\":\"9001\"}", "Authorization", auth);
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(account(id).path("currentBalance").decimalValue()).isEqualByComparingTo("60");
        assertThat(account(other).path("accountSuffix").asText()).isEqualTo("9001");
    }

    @Test
    void aBankAccountWithDigits_cannotBecomeAWalletUntilTheyAreCleared() {
        String id = idOf(create("BANK", ",\"accountSuffix\":\"1470\""));

        assertThat(put("/api/accounts/" + id, "{\"type\":\"WALLET\"}", "Authorization", auth).statusCode())
                .isEqualTo(400);
        assertThat(put("/api/accounts/" + id, "{\"type\":\"WALLET\",\"accountSuffix\":null}", "Authorization", auth)
                .statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> create(String type, String extra) {
        return post("/api/accounts",
                "{\"name\":\"t-" + UUID.randomUUID().toString().substring(0, 8) + "\",\"type\":\"" + type + "\""
                        + (extra.contains("currentBalance") ? "" : ",\"currentBalance\":0") + extra + "}",
                "Authorization", auth);
    }

    private String bank(String extra) {
        return idOf(create("BANK", extra));
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

    private JsonNode cashAccount() {
        for (JsonNode account : json(get("/api/accounts", "Authorization", auth)).path("data").path("accounts")) {
            if ("CASH".equals(account.path("type").asText())) {
                return account;
            }
        }
        throw new AssertionError("no Cash account");
    }
}
