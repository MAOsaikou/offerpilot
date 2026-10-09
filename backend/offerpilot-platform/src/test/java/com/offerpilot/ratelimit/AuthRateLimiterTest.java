package com.offerpilot.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRateLimiterTest {
    @Test
    void absentRedisUsesMemoryWithIndependentIpsAndBuckets() {
        var beans = new DefaultListableBeanFactory();
        var limiter = new AuthRateLimiter(beans.getBeanProvider(StringRedisTemplate.class));
        assertThat(limiter.consume("192.0.2.1", "guest", 1).allowed()).isTrue();
        assertThat(limiter.consume("192.0.2.1", "guest", 1).allowed()).isFalse();
        assertThat(limiter.consume("192.0.2.2", "guest", 1).allowed()).isTrue();
        assertThat(limiter.consume("192.0.2.1", "login", 1).allowed()).isTrue();
    }

    @Test
    void rejectedRequestsDoNotExtendWindowAndRetryAfterRoundsUp() {
        var clock = new MutableClock();
        var limiter = new AuthRateLimiter(null, clock);
        assertThat(limiter.consume("ip", "guest", 1)).isEqualTo(new AuthRateLimiter.Result(true, 0, 60));
        clock.now += 58_001;
        assertThat(limiter.consume("ip", "guest", 1)).isEqualTo(new AuthRateLimiter.Result(false, 0, 2));
        clock.now += 1_998;
        assertThat(limiter.consume("ip", "guest", 1)).isEqualTo(new AuthRateLimiter.Result(false, 0, 1));
        clock.now++;
        assertThat(limiter.consume("ip", "guest", 1)).isEqualTo(new AuthRateLimiter.Result(true, 0, 60));
    }

    @Test
    void concurrentMemoryRequestsCannotOvershootLimit() throws Exception {
        var limiter = new AuthRateLimiter(null, Clock.systemUTC());
        var executor = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        try {
            var attempts = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 50; i++) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    return limiter.consume("ip", "login", 3).allowed();
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get(5, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(3);
            assertThat(limiter.consume("ip", "login", 3).remaining()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void configuredRedisIsPreferredAndFailureDoesNotFallBackToFreshMemory() {
        var redis = new ScriptedRedisTemplate();
        redis.enqueue(List.of(0L, 3L, 1_001L));
        redis.enqueue(new RedisConnectionFailureException("isolated test failure"));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("redis", redis);
        var limiter = new AuthRateLimiter(beans.getBeanProvider(StringRedisTemplate.class));
        assertThat(limiter.consume("ip", "login", 3)).isEqualTo(new AuthRateLimiter.Result(false, 0, 2));
        assertThatThrownBy(() -> limiter.consume("ip", "login", 3)).isInstanceOf(RedisConnectionFailureException.class);
    }

    /** Avoids Mockito/Byte Buddy, which do not support JDK 25 in this toolchain. */
    private static final class ScriptedRedisTemplate extends StringRedisTemplate {
        private final Deque<Object> scripted = new ArrayDeque<>();

        void enqueue(Object value) {
            scripted.addLast(value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            Object next = scripted.removeFirst();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return (T) next;
        }
    }

    private static class MutableClock extends Clock {
        private long now = 1_000_000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }
}