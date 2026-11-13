package com.mnyace.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class SessionLimitTest extends AuthIntegrationTestBase {
    private static final String PASSWORD = "MemberPass123!";

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private SessionGate sessionGate;

    @BeforeEach
    void lowerTheCap() {
        ReflectionTestUtils.setField(sessionGate, "maxSessions", 2);
    }

    @AfterEach
    void restoreTheCap() {
        ReflectionTestUtils.setField(sessionGate, "maxSessions", 1000);
    }

    @Test
    void thirdSignIn_succeedsButIsRestrictedToSessionManagement() {
        String email = newMember();
        HttpResponse<String> first = signIn(email, "first");
        HttpResponse<String> second = signIn(email, "second");
        HttpResponse<String> third = signIn(email, "third");

        assertThat(third.statusCode()).isEqualTo(200);
        assertThat(restricted(first)).isFalse();
        assertThat(restricted(second)).isFalse();
        assertThat(restricted(third)).isTrue();

        assertThat(accounts(first).statusCode()).isEqualTo(200);
        HttpResponse<String> blocked = accounts(third);
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(json(blocked).path("error").path("code").asText()).isEqualTo("SESSION_LIMIT_REACHED");

        JsonNode listed = json(sessions(third)).path("data");
        assertThat(listed).hasSize(3);
        assertThat(entry(listed, "third").path("restricted").asBoolean()).isTrue();
        assertThat(entry(listed, "third").path("current").asBoolean()).isTrue();
        assertThat(entry(listed, "first").path("restricted").asBoolean()).isFalse();
    }

    @Test
    void signingOutAnOlderSession_liftsTheRestrictionAtOnce() {
        String email = newMember();
        signIn(email, "first");
        signIn(email, "second");
        HttpResponse<String> third = signIn(email, "third");
        String olderId = entry(json(sessions(third)).path("data"), "first").path("id").asText();

        HttpResponse<String> removed = delete("/api/sessions/" + olderId, "Authorization", "Bearer " + accessTokenOf(third));

        assertThat(removed.statusCode()).isEqualTo(200);
        assertThat(accounts(third).statusCode()).isEqualTo(200);
    }

    @Test
    void aNewSignIn_replacesTheSessionStillWaiting() {
        String email = newMember();
        signIn(email, "first");
        signIn(email, "second");
        HttpResponse<String> waiting = signIn(email, "waiting");
        HttpResponse<String> newest = signIn(email, "newest");

        assertThat(sessions(waiting).statusCode()).isEqualTo(401);
        assertThat(json(sessions(newest)).path("data")).hasSize(3);
    }

    @Test
    void aRestrictedSession_canStillRefreshAndLogOut() {
        String email = newMember();
        signIn(email, "first");
        signIn(email, "second");
        HttpResponse<String> third = signIn(email, "third");

        HttpResponse<String> refreshed = refresh(refreshTokenOf(third), "X-Forwarded-For", freshIp());

        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(restricted(refreshed)).isTrue();
        assertThat(post("/auth/logout", "{\"refreshToken\":\"" + refreshTokenOf(refreshed) + "\"}").statusCode())
                .isEqualTo(200);
    }

    @Test
    void signingOut_deletesTheSessionRows() {
        String email = newMember();
        HttpResponse<String> loggedOut = signIn(email, "logout");
        HttpResponse<String> removed = signIn(email, "removed");
        HttpResponse<String> keeper = signIn(email, "keeper");
        String removedId = entry(json(sessions(keeper)).path("data"), "removed").path("id").asText();
        // "keeper" is restricted, which is fine: managing sessions is exactly what it may do

        post("/auth/logout", "{\"refreshToken\":\"" + refreshTokenOf(loggedOut) + "\"}");
        delete("/api/sessions/" + removedId, "Authorization", "Bearer " + accessTokenOf(keeper));

        assertThat(rowsFor(email)).extracting(RefreshToken::getDeviceLabel).containsExactly("keeper");

        delete("/api/sessions", "Authorization", "Bearer " + accessTokenOf(keeper));
        assertThat(rowsFor(email)).isEmpty();
        assertThat(removed.statusCode()).isEqualTo(200);
    }

    @Test
    void rotation_prunesOldRevokedRows() {
        String email = newMember();
        HttpResponse<String> current = signIn(email, "busy");
        for (int i = 0; i < 6; i++) {
            current = refresh(refreshTokenOf(current), "X-Forwarded-For", freshIp());
            assertThat(current.statusCode()).isEqualTo(200);
        }

        assertThat(rowsFor(email).stream().filter(t -> t.getRevokedAt() != null)).hasSize(3);
        assertThat(rowsFor(email).stream().filter(t -> t.getRevokedAt() == null)).hasSize(1);
    }

    @Test
    void parallelSignIns_neverLeaveMoreThanOneSessionWaiting() throws Exception {
        String email = newMember();
        signIn(email, "first");
        signIn(email, "second");

        int callers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            String label = "burst-" + i;
            Callable<HttpResponse<String>> call = () -> {
                start.await();
                return signIn(email, label);
            };
            futures.add(pool.submit(call));
        }
        start.countDown();
        for (Future<HttpResponse<String>> f : futures) {
            assertThat(f.get().statusCode()).isEqualTo(200);
        }
        pool.shutdown();

        long live = rowsFor(email).stream().filter(t -> t.getRevokedAt() == null).count();
        assertThat(live).isLessThanOrEqualTo(3);
    }

    @Test
    void sessionList_showsWhereEachSessionSignedInFrom() {
        String email = newMember();
        String ip = freshIp();
        HttpResponse<String> response = post("/auth/signin", signInBody(email, "phone"),
                "X-Forwarded-For", ip, "User-Agent", "TestAgent/1.0");

        JsonNode session = entry(json(sessions(response)).path("data"), "phone");

        assertThat(session.path("ipAddress").asText()).isEqualTo(ip.substring(0, ip.lastIndexOf('.')) + ".0");
        assertThat(session.path("userAgent").asText()).isEqualTo("TestAgent/1.0");
    }

    @Test
    void anOverlongDeviceLabel_isRejected() {
        String email = newMember();

        HttpResponse<String> response = signIn(email, "x".repeat(101));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void anAuthenticatedRequest_refreshesLastActive() {
        String email = newMember();
        HttpResponse<String> first = signIn(email, "first");
        ageLastActive(email, "first", 600);

        accounts(first);

        assertThat(lastActiveOf(email, "first")).isAfter(java.time.LocalDateTime.now().minusSeconds(30));
    }

    @Test
    void requestsInsideTheThrottleWindow_doNotWrite() {
        String email = newMember();
        HttpResponse<String> first = signIn(email, "first");
        ageLastActive(email, "first", 10);
        java.time.LocalDateTime before = lastActiveOf(email, "first");

        accounts(first);

        assertThat(lastActiveOf(email, "first")).isEqualTo(before);
    }

    @Test
    void everySessionInTheList_keepsItsOwnLastActive_includingTheRestrictedOne() {
        String email = newMember();
        HttpResponse<String> first = signIn(email, "first");
        HttpResponse<String> second = signIn(email, "second");
        HttpResponse<String> third = signIn(email, "third");
        for (String label : List.of("first", "second", "third")) {
            ageLastActive(email, label, 600);
        }

        accounts(second);
        accounts(third);

        JsonNode listed = json(sessions(first)).path("data");
        java.time.Instant recent = java.time.Instant.now().minusSeconds(30);
        assertThat(java.time.Instant.parse(entry(listed, "first").path("lastActiveAt").asText())).isAfter(recent);
        assertThat(java.time.Instant.parse(entry(listed, "second").path("lastActiveAt").asText())).isAfter(recent);
        assertThat(java.time.Instant.parse(entry(listed, "third").path("lastActiveAt").asText())).isAfter(recent);
    }

    @Test
    void aRotation_keepsLastActiveCurrent() {
        String email = newMember();
        signIn(email, "first");
        ageLastActive(email, "first", 600);
        HttpResponse<String> refreshed = post("/auth/refresh",
                "{\"refreshToken\":\"" + json(signIn(email, "other")).path("data").path("refreshToken").asText() + "\"}");

        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(lastActiveOf(email, "other")).isAfter(java.time.LocalDateTime.now().minusSeconds(30));
    }

    private String newMember() {
        String email = "limit-" + UUID.randomUUID() + "@test.local";
        User user = new User();
        user.setFirstName("Limit");
        user.setLastName("Tester");
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole("MEMBER");
        users.save(user);
        return email;
    }

    private String signInBody(String email, String label) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"deviceLabel\":\"" + label + "\"}";
    }

    private HttpResponse<String> signIn(String email, String label) {
        return post("/auth/signin", signInBody(email, label), "X-Forwarded-For", freshIp());
    }

    private HttpResponse<String> sessions(HttpResponse<String> signedIn) {
        return get("/api/sessions", "Authorization", "Bearer " + accessTokenOf(signedIn));
    }

    private HttpResponse<String> accounts(HttpResponse<String> signedIn) {
        return get("/api/accounts", "Authorization", "Bearer " + accessTokenOf(signedIn));
    }

    private static boolean restricted(HttpResponse<String> response) {
        return json(response).path("data").path("sessionRestricted").asBoolean();
    }

    private static JsonNode entry(JsonNode sessions, String label) {
        for (JsonNode session : sessions) {
            if (label.equals(session.path("deviceLabel").asText())) {
                return session;
            }
        }
        throw new AssertionError("no session labelled " + label);
    }

    private java.util.List<RefreshToken> rowsFor(String email) {
        String userId = users.findByEmailIgnoreCase(email).orElseThrow().getId();
        return refreshTokens.findAllByUser_Id(userId);
    }

    private void ageLastActive(String email, String label, long secondsAgo) {
        for (RefreshToken row : rowsFor(email)) {
            if (label.equals(row.getDeviceLabel()) && row.getRevokedAt() == null) {
                row.setLastActiveAt(java.time.LocalDateTime.now().minusSeconds(secondsAgo));
                refreshTokens.save(row);
            }
        }
    }

    private java.time.LocalDateTime lastActiveOf(String email, String label) {
        return rowsFor(email).stream()
                .filter(row -> label.equals(row.getDeviceLabel()) && row.getRevokedAt() == null)
                .findFirst().orElseThrow().getLastActiveAt();
    }
}
