package com.mnyace.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.mnyace.auth.AuthIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BankCatalogTest extends AuthIntegrationTestBase {
    @Autowired
    private JdbcTemplate jdbc;

    private String auth;

    @BeforeEach
    void signInAsAdmin() {
        auth = "Bearer " + accessTokenOf(signInAdmin("X-Forwarded-For", freshIp()));
    }

    @Test
    void theList_needsASignedInUser() {
        assertThat(get("/api/banks").statusCode()).isEqualTo(401);
    }

    @Test
    void theList_isOrderedByDisplayOrderThenName_andCarriesCodeNameAndBothColours() {
        HttpResponse<String> response = get("/api/banks", "Authorization", auth);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode banks = json(response).path("data");
        assertThat(banks.get(0).path("code").asText()).isEqualTo("SBI");
        assertThat(banks.get(0).path("name").asText()).isEqualTo("State Bank of India");
        assertThat(banks.get(0).fieldNames()).toIterable().containsExactly("code", "name", "colorLight", "colorDark");
        List<String> served = new ArrayList<>();
        banks.forEach(b -> served.add(b.path("code").asText()));
        assertThat(served).isEqualTo(jdbc.queryForList(
                "SELECT code FROM banks WHERE is_active = 1 ORDER BY display_order, name", String.class));
    }

    @Test
    void anInactiveBank_isLeftOut() {
        jdbc.update("UPDATE banks SET is_active = 0 WHERE code = 'IPPB'");
        try {
            assertThat(codes()).doesNotContain("IPPB").contains("SBI");
        } finally {
            jdbc.update("UPDATE banks SET is_active = 1 WHERE code = 'IPPB'");
        }
        assertThat(codes()).contains("IPPB");
    }

    @Test
    void theList_isCacheableForADay_andAnUnchangedListAnswers304() {
        HttpResponse<String> response = get("/api/banks", "Authorization", auth);

        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("max-age=86400");
        String etag = response.headers().firstValue("ETag").orElseThrow();

        HttpResponse<String> again = get("/api/banks", "Authorization", auth, "If-None-Match", etag);
        assertThat(again.statusCode()).isEqualTo(304);
        assertThat(again.body()).isEmpty();
    }

    @Test
    void everyCatalogCode_isTwoToTenUpperCaseLettersOrDigits() {
        assertThat(codes()).allMatch(code -> code.matches("[A-Z0-9]{2,10}"));
        assertThat(codes()).contains("SBI", "HDFC", "ICICI", "AXIS", "BOI", "IPPB", "IDFCFIRST");
    }

    @Test
    void everyBankColour_isSixHexDigits_inBothModes() {
        json(get("/api/banks", "Authorization", auth)).path("data").forEach(bank -> {
            assertThat(bank.path("colorLight").asText()).matches("#[0-9A-F]{6}");
            assertThat(bank.path("colorDark").asText()).matches("#[0-9A-F]{6}");
        });
    }

    private List<String> codes() {
        List<String> codes = new ArrayList<>();
        json(get("/api/banks", "Authorization", auth)).path("data").forEach(b -> codes.add(b.path("code").asText()));
        return codes;
    }
}
