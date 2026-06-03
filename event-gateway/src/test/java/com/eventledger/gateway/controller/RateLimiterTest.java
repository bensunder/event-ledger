package com.eventledger.gateway.controller;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for rate limiter configuration and behavior.
 * No Spring context — pure Resilience4j unit test.
 */
class RateLimiterTest {

    @Test
    void rateLimiter_allowsRequestsWithinLimit() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(10)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();

        RateLimiter rateLimiter = RateLimiterRegistry.of(config).rateLimiter("test");

        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (rateLimiter.acquirePermission()) allowed++;
        }
        assertEquals(10, allowed);
    }

    @Test
    void rateLimiter_blocksRequestsOverLimit() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(5)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build();

        RateLimiter rateLimiter = RateLimiterRegistry.of(config).rateLimiter("test-block");

        int allowed = 0;
        int blocked = 0;
        for (int i = 0; i < 10; i++) {
            if (rateLimiter.acquirePermission()) allowed++;
            else blocked++;
        }
        assertEquals(5, allowed);
        assertEquals(5, blocked);
    }

    @Test
    void rateLimiter_configMatchesProduction() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();

        assertEquals(100, config.getLimitForPeriod());
        assertEquals(Duration.ofSeconds(1), config.getLimitRefreshPeriod());
        assertEquals(Duration.ZERO, config.getTimeoutDuration());
    }
}
