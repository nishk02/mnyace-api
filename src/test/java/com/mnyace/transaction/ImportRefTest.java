package com.mnyace.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.mnyace.auth.AuthIntegrationTestBase;
import com.mnyace.auth.User;
import com.mnyace.auth.UserRepository;
import com.mnyace.shared.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportRefTest extends AuthIntegrationTestBase {
    private static final String PASSWORD = "MemberPass123!";

    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;

    private String auth;
    private String accountId;

    @BeforeEach
    void signInWithABankAccount() {
        auth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
        accountId = newBankAccount(auth);
    }

    @Test
    void aRepeatedImportRef_isRefusedAndChangesNothing() {
        String ref = ref();
        assertThat(expense(auth, accountId, ref).statusCode()).isEqualTo(200);

        HttpResponse<String> repeat = expense(auth, accountId, ref);

        assertThat(repeat.statusCode()).isEqualTo(409);
        JsonNode error = json(repeat).path("error");
        assertThat(error.path("code").asText()).isEqualTo("DUPLICATE_IMPORT");
        assertThat(error.path("details").path("transactionId").asText()).isNotBlank();
        assertThat(balanceOf(auth, accountId)).isEqualByComparingTo("900");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE import_ref = ?", Integer.class, ref))
                .isEqualTo(1);
    }

    @Test
    void withoutAnImportRef_identicalTransactionsAreBothCreated() {
        assertThat(expense(auth, accountId, null).statusCode()).isEqualTo(200);
        assertThat(expense(auth, accountId, null).statusCode()).isEqualTo(200);
        // a blank value counts as none
        assertThat(expense(auth, accountId, "").statusCode()).isEqualTo(200);
        assertThat(expense(auth, accountId, " ").statusCode()).isEqualTo(200);
    }

    @Test
    void theSameImportRef_isFineForAnotherUser() {
        String ref = ref();
        assertThat(expense(auth, accountId, ref).statusCode()).isEqualTo(200);

        String email = "import-" + UUID.randomUUID() + "@test.local";
        User member = new User();
        member.setFirstName("Import");
        member.setLastName("Tester");
        member.setEmail(email);
        member.setPasswordHash(passwordEncoder.encode(PASSWORD));
        member.setRole("MEMBER");
        users.save(member);
        String memberAuth = "Bearer " + accessTokenOf(signIn(email, PASSWORD, "X-Forwarded-For", freshIp()));

        assertThat(expense(memberAuth, newBankAccount(memberAuth), ref).statusCode()).isEqualTo(200);
    }

    @Test
    void anImportRef_over64Characters_isRejected() {
        assertThat(expense(auth, accountId, "r".repeat(65)).statusCode()).isEqualTo(400);
        assertThat(expense(auth, accountId, "r".repeat(64)).statusCode()).isEqualTo(200);
    }

    @Test
    void theDatabase_refusesTheSameRefTwiceForOneUser() {
        String first = ref();
        String second = ref();
        expense(auth, accountId, first);
        expense(auth, accountId, second);

        assertThatThrownBy(() -> jdbc.update("UPDATE transactions SET import_ref = ? WHERE import_ref = ?", first, second))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UNIQUE constraint failed");
    }

    @Test
    void theSource_defaultsToManual_andAcceptsSms() {
        String manual = idOf(expense(auth, accountId, null));
        String sms = idOf(post("/api/transactions", body(accountId, ",\"importRef\":\"" + ref() + "\",\"source\":\"SMS\""),
                "Authorization", auth));

        assertThat(sourceOf(manual)).isEqualTo("MANUAL");
        assertThat(sourceOf(sms)).isEqualTo("SMS");
        assertThat(post("/api/transactions", body(accountId, ",\"source\":\"EMAIL\""), "Authorization", auth).statusCode())
                .isEqualTo(400);
    }

    private String idOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response).path("data").path("id").asText();
    }

    private String sourceOf(String transactionId) {
        return json(get("/api/transactions/" + transactionId, "Authorization", auth)).path("data").path("source").asText();
    }

    private static String body(String account, String extra) {
        return "{\"date\":\"" + AppClock.today() + "\",\"type\":\"VARIABLE_EXPENSE\",\"categoryId\":\"cat-01\",\"accountId\":\""
                + account + "\",\"amount\":100" + extra + "}";
    }

    private HttpResponse<String> expense(String authorization, String account, String importRef) {
        String ref = importRef == null ? "" : ",\"importRef\":\"" + importRef + "\"";
        return post("/api/transactions",
                "{\"date\":\"" + AppClock.today() + "\",\"type\":\"VARIABLE_EXPENSE\",\"categoryId\":\"cat-01\",\"accountId\":\""
                        + account + "\",\"amount\":100" + ref + "}",
                "Authorization", authorization);
    }

    private String newBankAccount(String authorization) {
        HttpResponse<String> response = post("/api/accounts",
                "{\"name\":\"imp-" + UUID.randomUUID().toString().substring(0, 8) + "\",\"type\":\"BANK\",\"currentBalance\":1000}",
                "Authorization", authorization);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).path("data").path("id").asText();
    }

    private java.math.BigDecimal balanceOf(String authorization, String account) {
        return json(get("/api/accounts/" + account, "Authorization", authorization)).path("data").path("currentBalance")
                .decimalValue();
    }

    private static String ref() {
        return "sms-" + UUID.randomUUID();
    }
}
