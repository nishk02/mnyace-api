package com.mnyace.shared.health;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthControllerTest {
    @Test
    void whenTheDatabaseAnswers_isUp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select 1", Integer.class)).thenReturn(1);

        ResponseEntity<Map<String, String>> response = new HealthController(jdbc).health();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of("status", "UP"));
    }

    @Test
    void whenTheDatabaseFails_is503Down_andStillNotCached() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select 1", Integer.class)).thenThrow(new DataAccessResourceFailureException("down"));

        ResponseEntity<Map<String, String>> response = new HealthController(jdbc).health();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isEqualTo(Map.of("status", "DOWN"));
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void aBurstOfCalls_touchesTheDatabaseOnce_untilTwoSecondsPass() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select 1", Integer.class)).thenReturn(1);
        AtomicLong now = new AtomicLong(1_000);
        HealthController controller = new HealthController(jdbc, now::get);

        for (int i = 0; i < 50; i++) {
            controller.health();
        }
        verify(jdbc, times(1)).queryForObject("select 1", Integer.class);

        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1_900));
        controller.health();
        verify(jdbc, times(1)).queryForObject("select 1", Integer.class);

        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
        controller.health();
        verify(jdbc, times(2)).queryForObject("select 1", Integer.class);
    }

    @Test
    void aDownAnswerIsReusedToo_andRecoversAfterTwoSeconds() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("down"))
                .thenReturn(1);
        AtomicLong now = new AtomicLong(1_000);
        HealthController controller = new HealthController(jdbc, now::get);

        assertThat(controller.health().getStatusCode().value()).isEqualTo(503);
        assertThat(controller.health().getStatusCode().value()).isEqualTo(503);
        verify(jdbc, times(1)).queryForObject("select 1", Integer.class);

        now.addAndGet(TimeUnit.SECONDS.toNanos(3));
        assertThat(controller.health().getStatusCode().value()).isEqualTo(200);
    }
}
