package com.moneyflow.shared;

import com.moneyflow.auth.AuthIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class InputBoundsTest extends AuthIntegrationTestBase {
    private String auth;

    @BeforeEach
    void signInAsAdmin() {
        auth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
    }

    @Test
    void createAccount_withReasonableInput_succeeds() {
        assertThat(createAccount("Savings", "1500.50").statusCode()).isEqualTo(201);
    }

    @Test
    void createAccount_withAnOverlongName_isRejected() {
        assertThat(createAccount("n".repeat(300), "10").statusCode()).isEqualTo(400);
    }

    @Test
    void createAccount_withAnAbsurdBalance_isRejected() {
        assertThat(createAccount("Rich", "1" + "0".repeat(30)).statusCode()).isEqualTo(400);
    }

    @Test
    void createAccount_withTooManyDecimalPlaces_isRejected() {
        assertThat(createAccount("Precise", "10.123").statusCode()).isEqualTo(400);
    }

    @Test
    void transactionNotes_over250Characters_areRejected() {
        HttpResponse<String> response = createTransaction("n".repeat(251));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("error").path("message").asText()).contains("250");
    }

    @Test
    void transactionNotes_of250Characters_passValidation() {
        // fake ids: validation passes and the service answers 404, never a 400 about the notes
        assertThat(createTransaction("n".repeat(250)).statusCode()).isNotEqualTo(400);
    }

    @Test
    void transactionList_capsThePageSize() {
        HttpResponse<String> response = get("/api/transactions?size=5000", "Authorization", auth);

        assertThat(response.statusCode()).isEqualTo(200);
        int size = json(response).path("data").path("transactions").path("size").asInt();
        assertThat(size).isLessThanOrEqualTo(200);
    }

    @Test
    void transactionList_withAnUnknownSortField_isABadRequestNotAServerError() {
        HttpResponse<String> response = get("/api/transactions?sort=doesNotExist,asc", "Authorization", auth);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void goalReorder_withoutItems_isABadRequestNotAServerError() {
        HttpResponse<String> response = put("/api/goals/reorder", "{}", "Authorization", auth);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> createTransaction(String notes) {
        return post("/api/transactions",
                "{\"date\":\"2026-01-15\",\"type\":\"VARIABLE_EXPENSE\",\"categoryId\":\"c\",\"accountId\":\"a\","
                        + "\"amount\":10,\"notes\":\"" + notes + "\"}",
                "Authorization", auth);
    }

    private HttpResponse<String> createAccount(String name, String balance) {
        return post("/api/accounts",
                "{\"name\":\"" + name + "\",\"type\":\"BANK\",\"currentBalance\":" + balance + "}",
                "Authorization", auth);
    }
}
