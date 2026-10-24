package com.moneyflow.shared.security.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimitConfig {

    @Bean
    public RateLimiterService signinIpRateLimiter(
            @Value("${rate-limit.signin.ip.capacity}") long capacity,
            @Value("${rate-limit.signin.ip.period-seconds}") long periodSeconds
    ) {
        return new RateLimiterService(capacity, Duration.ofSeconds(periodSeconds));
    }

    @Bean
    public RateLimiterService signinEmailRateLimiter(
            @Value("${rate-limit.signin.email.capacity}") long capacity,
            @Value("${rate-limit.signin.email.period-seconds}") long periodSeconds
    ) {
        return new RateLimiterService(capacity, Duration.ofSeconds(periodSeconds));
    }

    @Bean
    public RateLimiterService refreshIpRateLimiter(
            @Value("${rate-limit.refresh.ip.capacity}") long capacity,
            @Value("${rate-limit.refresh.ip.period-seconds}") long periodSeconds
    ) {
        return new RateLimiterService(capacity, Duration.ofSeconds(periodSeconds));
    }
}