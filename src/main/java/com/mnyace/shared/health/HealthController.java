package com.mnyace.shared.health;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Public readiness check: UP once the database answers, DOWN (503) when it does not; the answer is reused for 2 seconds. */
@RestController
public class HealthController {
    private static final long REUSE_NANOS = TimeUnit.SECONDS.toNanos(2);

    private record Checked(boolean up, long atNanos) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final LongSupplier nanoClock;
    private volatile Checked last;

    @Autowired
    public HealthController(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, System::nanoTime);
    }

    HealthController(JdbcTemplate jdbcTemplate, LongSupplier nanoClock) {
        this.jdbcTemplate = jdbcTemplate;
        this.nanoClock = nanoClock;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        boolean up = isUp();
        return ResponseEntity
                .status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("status", up ? "UP" : "DOWN"));
    }

    /** A burst of calls touches the database at most once every 2 seconds, so a flood cannot compete for its connections. */
    private boolean isUp() {
        Checked recent = last;
        if (recent != null && nanoClock.getAsLong() - recent.atNanos() < REUSE_NANOS) {
            return recent.up();
        }
        synchronized (this) {
            long now = nanoClock.getAsLong();
            recent = last;
            if (recent != null && now - recent.atNanos() < REUSE_NANOS) {
                return recent.up();
            }
            boolean up = databaseAnswers();
            last = new Checked(up, now);
            return up;
        }
    }

    private boolean databaseAnswers() {
        try {
            return Integer.valueOf(1).equals(jdbcTemplate.queryForObject("select 1", Integer.class));
        } catch (DataAccessException e) {
            return false;
        }
    }
}
