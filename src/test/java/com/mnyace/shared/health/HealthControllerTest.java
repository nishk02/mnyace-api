package com.mnyace.shared.health;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
}
