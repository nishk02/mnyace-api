package com.mnyace.auth;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshConcurrencyTest extends AuthIntegrationTestBase {

    @Test
    void parallelRefreshesOfOneToken_neverReturn500_andExactlyOneSucceeds() throws Exception {
        String ip = freshIp();
        String token = refreshTokenOf(signInAdmin("X-Forwarded-For", ip));

        int callers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Callable<HttpResponse<String>> call = () -> {
                start.await();
                return refresh(token, "X-Forwarded-For", ip);
            };
            futures.add(pool.submit(call));
        }
        start.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<HttpResponse<String>> f : futures) {
            statuses.add(f.get().statusCode());
        }
        pool.shutdown();

        assertThat(statuses).doesNotContain(500);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
    }
}
