package com.mnyace.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.mnyace.auth.AuthIntegrationTestBase;
import com.mnyace.auth.User;
import com.mnyace.auth.UserRepository;
import com.mnyace.shared.util.AppClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.http.HttpResponse;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantMemoryTest extends AuthIntegrationTestBase {
    private static final String PASSWORD = "MemberPass123!";
    private static final String GROCERIES = "cat-12";
    private static final String DINING = "cat-09";
    private static final String TRAVEL = "cat-10";
    private static final String SALARY = "cat-03";

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

    // The database is shared with other test classes, and their analytics totals must not see these rows.
    @AfterEach
    void removeWhatWasCreated() {
        jdbc.update("DELETE FROM transactions WHERE account_id = ?", accountId);
        jdbc.update("DELETE FROM accounts WHERE id = ?", accountId);
    }

    @Test
    void aMerchant_isStored_andNeverReturned() {
        String name = letters();
        HttpResponse<String> response = spend(auth, accountId, GROCERIES, name + " 1234");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("data").has("merchant")).isFalse();
        assertThat(json(response).path("data").has("merchantKey")).isFalse();
        String id = json(response).path("data").path("id").asText();
        assertThat(jdbc.queryForObject("SELECT merchant FROM transactions WHERE id = ?", String.class, id))
                .isEqualTo(name + " 1234");
        assertThat(jdbc.queryForObject("SELECT merchant_key FROM transactions WHERE id = ?", String.class, id))
                .isEqualTo(name);
        assertThat(json(get("/api/transactions/" + id, "Authorization", auth)).path("data").has("merchant")).isFalse();
    }

    @Test
    void aMerchantOver100Characters_isRejected_and100IsFine() {
        assertThat(spend(auth, accountId, GROCERIES, "m".repeat(101)).statusCode()).isEqualTo(400);
        assertThat(spend(auth, accountId, GROCERIES, "m".repeat(100)).statusCode()).isEqualTo(200);
    }

    @Test
    void withoutAMerchant_nothingIsStored_andABlankOneCountsAsNone() {
        String none = idOf(spend(auth, accountId, GROCERIES, null));
        String blank = idOf(spend(auth, accountId, GROCERIES, "   "));

        for (String id : new String[]{none, blank}) {
            assertThat(jdbc.queryForObject("SELECT merchant FROM transactions WHERE id = ?", String.class, id)).isNull();
            assertThat(jdbc.queryForObject("SELECT merchant_key FROM transactions WHERE id = ?", String.class, id)).isNull();
        }
    }

    @Test
    void aMerchantWithNothingLeftAfterNormalising_isStoredButNeverLearnedFrom() {
        String id = idOf(spend(auth, accountId, GROCERIES, "123 ***"));

        assertThat(jdbc.queryForObject("SELECT merchant FROM transactions WHERE id = ?", String.class, id)).isEqualTo("123 ***");
        assertThat(jdbc.queryForObject("SELECT merchant_key FROM transactions WHERE id = ?", String.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE id = ? AND merchant_key IS NULL",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void importRefBehaviour_isUnchangedByAMerchant() {
        String ref = "m-" + UUID.randomUUID();
        String body = body(accountId, GROCERIES, letters(), ",\"importRef\":\"" + ref + "\"");

        assertThat(post("/api/transactions", body, "Authorization", auth).statusCode()).isEqualTo(200);
        HttpResponse<String> repeat = post("/api/transactions", body, "Authorization", auth);
        assertThat(repeat.statusCode()).isEqualTo(409);
        assertThat(json(repeat).path("error").path("code").asText()).isEqualTo("DUPLICATE_IMPORT");
    }

    @Test
    void theMostUsedCategoryWins_andFollowsCorrections() {
        String name = letters();
        String first = idOf(spend(auth, accountId, GROCERIES, name + " 11"));
        String second = idOf(spend(auth, accountId, GROCERIES, name + " 22"));
        idOf(spend(auth, accountId, GROCERIES, name + " 33"));

        JsonNode learned = learned(name);
        assertThat(learned.path("categoryId").asText()).isEqualTo(GROCERIES);
        assertThat(learned.path("count").asInt()).isEqualTo(3);
        assertThat(learned.path("merchantKey").asText()).isEqualTo(name);

        assertThat(put("/api/transactions/" + first, "{\"categoryId\":\"" + DINING + "\"}", "Authorization", auth).statusCode())
                .isEqualTo(200);
        assertThat(put("/api/transactions/" + second, "{\"categoryId\":\"" + DINING + "\"}", "Authorization", auth).statusCode())
                .isEqualTo(200);

        learned = learned(name);
        assertThat(learned.path("categoryId").asText()).isEqualTo(DINING);
        assertThat(learned.path("count").asInt()).isEqualTo(2);
    }

    @Test
    void aTie_goesToTheMostRecentlyUsedCategory() throws InterruptedException {
        String name = letters();
        idOf(spend(auth, accountId, GROCERIES, name));
        Thread.sleep(20);
        idOf(spend(auth, accountId, DINING, name));

        assertThat(learned(name).path("categoryId").asText()).isEqualTo(DINING);
    }

    @Test
    void differentSpellings_ofOneMerchant_countTogether_andTheLatestSpellingIsShown() throws InterruptedException {
        String name = letters();
        idOf(spend(auth, accountId, GROCERIES, name.toLowerCase() + " 1"));
        Thread.sleep(20);
        idOf(spend(auth, accountId, GROCERIES, name + "*"));

        JsonNode learned = learned(name);
        assertThat(learned.path("count").asInt()).isEqualTo(2);
        assertThat(learned.path("merchant").asText()).isEqualTo(name + "*");
    }

    @Test
    void deletedTransactions_stopCounting() {
        String name = letters();
        String only = idOf(spend(auth, accountId, GROCERIES, name));
        assertThat(learned(name)).isNotNull();

        assertThat(delete("/api/transactions/" + only, "Authorization", auth).statusCode()).isEqualTo(200);

        assertThat(learned(name)).isNull();
    }

    @Test
    void anInactiveCategory_isSkipped_fallingBackToTheNextBest_orOmittingTheMerchant() {
        String both = letters();
        String onlyInactive = letters();
        idOf(spend(auth, accountId, TRAVEL, both));
        idOf(spend(auth, accountId, TRAVEL, both));
        idOf(spend(auth, accountId, GROCERIES, both));
        idOf(spend(auth, accountId, TRAVEL, onlyInactive));

        jdbc.update("UPDATE categories SET is_active = 0 WHERE id = ?", TRAVEL);
        try {
            assertThat(learned(both).path("categoryId").asText()).isEqualTo(GROCERIES);
            assertThat(learned(both).path("count").asInt()).isEqualTo(1);
            assertThat(learned(onlyInactive)).isNull();
        } finally {
            jdbc.update("UPDATE categories SET is_active = 1 WHERE id = ?", TRAVEL);
        }
    }

    @Test
    void aUserNeverSeesAnotherUsersMerchants() {
        String name = letters();
        idOf(spend(auth, accountId, GROCERIES, name));

        String memberAuth = newMemberAuth();
        JsonNode theirs = json(get("/api/transactions/merchant-categories", "Authorization", memberAuth))
                .path("data").path("merchantCategories");
        assertThat(theirs).isEmpty();
    }

    @Test
    void theFlowTypeFilter_andTheLimit_narrowTheList() {
        String expense = letters();
        String income = letters();
        idOf(spend(auth, accountId, GROCERIES, expense));
        idOf(post("/api/transactions", body(accountId, SALARY, income, "").replace("VARIABLE_EXPENSE", "INCOME"),
                "Authorization", auth));

        assertThat(learned(income, "?flowType=INCOME")).isNotNull();
        assertThat(learned(expense, "?flowType=INCOME")).isNull();
        assertThat(learned(expense, "?flowType=EXPENSE")).isNotNull();
        assertThat(learned(income, "?flowType=EXPENSE")).isNull();

        JsonNode limited = json(get("/api/transactions/merchant-categories?limit=1", "Authorization", auth))
                .path("data").path("merchantCategories");
        assertThat(limited).hasSize(1);
    }

    private JsonNode learned(String key) {
        return learned(key, "");
    }

    private JsonNode learned(String key, String query) {
        JsonNode list = json(get("/api/transactions/merchant-categories" + query, "Authorization", auth))
                .path("data").path("merchantCategories");
        for (JsonNode entry : list) {
            if (key.equals(entry.path("merchantKey").asText())) {
                return entry;
            }
        }
        return null;
    }

    private HttpResponse<String> spend(String authorization, String account, String category, String merchant) {
        return post("/api/transactions", body(account, category, merchant, ""), "Authorization", authorization);
    }

    private static String body(String account, String category, String merchant, String extra) {
        String m = merchant == null ? "" : ",\"merchant\":\"" + merchant + "\"";
        return "{\"date\":\"" + AppClock.today() + "\",\"type\":\"VARIABLE_EXPENSE\",\"categoryId\":\"" + category
                + "\",\"accountId\":\"" + account + "\",\"amount\":10" + m + extra + "}";
    }

    private String idOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response).path("data").path("id").asText();
    }

    private String newBankAccount(String authorization) {
        HttpResponse<String> response = post("/api/accounts",
                "{\"name\":\"mm-" + UUID.randomUUID().toString().substring(0, 8) + "\",\"type\":\"BANK\",\"accountSuffix\":\"" + freshDigits() + "\",\"currentBalance\":100000}",
                "Authorization", authorization);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).path("data").path("id").asText();
    }

    private String newMemberAuth() {
        String email = "merchant-" + UUID.randomUUID() + "@test.local";
        User member = new User();
        member.setFirstName("Merchant");
        member.setLastName("Tester");
        member.setEmail(email);
        member.setPasswordHash(passwordEncoder.encode(PASSWORD));
        member.setRole("MEMBER");
        users.save(member);
        return "Bearer " + accessTokenOf(signIn(email, PASSWORD, "X-Forwarded-For", freshIp()));
    }

    /** Letters only, because normalising strips digits and a test name must survive it unchanged. */
    private static String letters() {
        Random random = new Random();
        StringBuilder builder = new StringBuilder("ZZ");
        for (int i = 0; i < 10; i++) {
            builder.append((char) ('A' + random.nextInt(26)));
        }
        return builder.toString();
    }
}
