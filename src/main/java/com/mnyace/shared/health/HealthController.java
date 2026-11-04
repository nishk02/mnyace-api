package com.mnyace.shared.health;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public readiness check: UP once the database answers, DOWN (503) when it does not. */
@RestController
@RequiredArgsConstructor
public class HealthController {
    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        boolean up = databaseAnswers();
        return ResponseEntity
                .status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("status", up ? "UP" : "DOWN"));
    }

    private boolean databaseAnswers() {
        try {
            return Integer.valueOf(1).equals(jdbcTemplate.queryForObject("select 1", Integer.class));
        } catch (DataAccessException e) {
            return false;
        }
    }
}
