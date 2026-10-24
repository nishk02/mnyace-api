package com.moneyflow.shared.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

import java.time.Duration;

/**
 * Defines a rate-limiting rule with a bucket capacity and refill period,
 * applied independently to each key (e.g., an IP or email address).
 * Uses a Caffeine cache to automatically remove inactive buckets and
 * prevent unbounded memory growth. Expired buckets lose their rate-limit
 * history, allowing the key to start fresh on its next request.
 */
public class RateLimiterService {
    private final Cache<String, Bucket> buckets;
    private final Bandwidth bandwidth;

    public RateLimiterService(long capacity, Duration period) {
        this.bandwidth = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, period)
                .build();
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(period.multipliedBy(2))
                .maximumSize(100_000)
                .build();
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {}

    public Decision consume(String key) {
        Bucket bucket = buckets.get(key, k -> Bucket.builder().addLimit(bandwidth).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Decision(true, 0);
        }
        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000);
        return new Decision(false, retryAfterSeconds);
    }
}