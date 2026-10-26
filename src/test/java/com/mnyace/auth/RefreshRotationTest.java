package com.mnyace.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshRotationTest extends AuthIntegrationTestBase {

    @Test
    void refresh_rotatesToken_andIssuesNewAccessToken() {
        String ip = freshIp();
        String first = refreshTokenOf(signInAdmin("X-Forwarded-For", ip));

        var response = refresh(first, "X-Forwarded-For", ip);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(refreshTokenOf(response)).isNotBlank().isNotEqualTo(first);
        assertThat(accessTokenOf(response)).isNotBlank();
    }

    @Test
    void immediateDuplicate_isRefusedButKeepsTheSessionAlive() {
        String ip = freshIp();
        String first = refreshTokenOf(signInAdmin("X-Forwarded-For", ip));
        String second = refreshTokenOf(refresh(first, "X-Forwarded-For", ip));

        assertThat(refresh(first, "X-Forwarded-For", ip).statusCode()).isEqualTo(401);
        assertThat(refresh(second, "X-Forwarded-For", ip).statusCode()).isEqualTo(200);
    }

    @Test
    void lateReplayOfRotatedToken_revokesTheWholeFamily() throws Exception {
        String ip = freshIp();
        String first = refreshTokenOf(signInAdmin("X-Forwarded-For", ip));
        String second = refreshTokenOf(refresh(first, "X-Forwarded-For", ip));

        Thread.sleep(5_500); // past the duplicate-request window

        assertThat(refresh(first, "X-Forwarded-For", ip).statusCode()).isEqualTo(401);
        assertThat(refresh(second, "X-Forwarded-For", ip).statusCode()).isEqualTo(401);
    }

    @Test
    void unknownToken_isRefused() {
        assertThat(refresh("not-a-real-token", "X-Forwarded-For", freshIp()).statusCode()).isEqualTo(401);
    }
}
