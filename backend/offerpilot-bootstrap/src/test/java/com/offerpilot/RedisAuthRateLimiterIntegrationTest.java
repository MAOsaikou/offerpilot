package com.offerpilot;

import com.offerpilot.ratelimit.AuthRateLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes the real Lua script against disposable Redis, without application/dev services. */
@Testcontainers
class RedisAuthRateLimiterIntegrationTest {
    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
    }

    @AfterAll
    static void close() {
        if (connections != null) connections.destroy();
    }

    private AuthRateLimiter instance() {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("redis", redis);
        return new AuthRateLimiter(beans.getBeanProvider(StringRedisTemplate.class));
    }

    @Test
    void instancesShareAtomicLimitUnderConcurrencyAndKeepOtherIpsAndRoutesIndependent() throws Exception {
        var first = instance();
        var second = instance();
        var executor = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        try {
            var attempts = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 50; i++) {
                var limiter = i % 2 == 0 ? first : second;
                attempts.add(executor.submit(() -> {
                    start.await();
                    return limiter.consume("192.0.2.20", "login", 3).allowed();
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(3);
            assertThat(redis.getExpire("offerpilot:rate-limit:auth:login:192.0.2.20", TimeUnit.MILLISECONDS)).isBetween(1L, 60_000L);
            assertThat(second.consume("192.0.2.20", "login", 3).allowed()).isFalse();
            assertThat(second.consume("192.0.2.21", "login", 3).allowed()).isTrue();
            assertThat(second.consume("192.0.2.20", "guest", 3).allowed()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectionPreservesRedisExpiryAndExpiredWindowAllowsRequestsAgain() throws Exception {
        var limiter = instance();
        assertThat(limiter.consume("192.0.2.22", "guest", 1).allowed()).isTrue();
        String key = "offerpilot:rate-limit:auth:guest:192.0.2.22";
        // Shorten this disposable key's TTL so the real expiration test stays fast.
        assertThat(redis.expire(key, Duration.ofMillis(1_500))).isTrue();
        var rejected = instance().consume("192.0.2.22", "guest", 1);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isZero();
        assertThat(rejected.retryAfterSeconds()).isBetween(1L, 2L);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 1_500L);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Boolean.TRUE.equals(redis.hasKey(key))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Redis key did not expire within 5s: " + key);
            }
            Thread.sleep(50);
        }
        assertThat(limiter.consume("192.0.2.22", "guest", 1).allowed()).isTrue();
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 60_000L);
    }
}
