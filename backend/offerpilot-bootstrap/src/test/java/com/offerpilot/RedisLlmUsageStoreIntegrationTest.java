package com.offerpilot;

import com.offerpilot.llm.quota.RedisLlmUsageStore;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real Lua/shared counters against disposable Redis; never touches dev services. */
@Testcontainers
class RedisLlmUsageStoreIntegrationTest {
    @Container static GenericContainer<?> container = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory connections;
    static StringRedisTemplate redis;
    @BeforeAll static void connect() {
        connections = new LettuceConnectionFactory(container.getHost(), container.getMappedPort(6379));
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
    }
    @AfterAll static void close() { if (connections != null) connections.destroy(); }
    @Test void eraseRemovesOnlyOwnersCountersAndFencesLateUsageAcrossInstances() {
        var first=new RedisLlmUsageStore(redis);var second=new RedisLlmUsageStore(redis);
        Instant reset=Instant.now().plusSeconds(3600);
        first.check("deleted",1,"day",10,1000,reset,reset,true);
        first.addTokens("deleted","day",50,reset);
        first.addTokens("survivor","day",75,reset);
        assertThat(second.tokens("deleted","day")).isEqualTo(50);
        first.erase("deleted");
        second.addTokens("deleted","day",99,reset);
        assertThat(second.tokens("deleted","day")).isZero();
        assertThat(second.tokens("survivor","day")).isEqualTo(75);
        assertThat(redis.hasKey("offerpilot:llm:{deleted}:rpm:1")).isFalse();
        assertThat(redis.hasKey("offerpilot:llm:{deleted}:tokens:day")).isFalse();
        assertThat(redis.opsForValue().get("offerpilot:llm:{survivor}:tokens:day")).isEqualTo("75");
        assertThat(redis.getExpire("offerpilot:llm:{deleted}:revoked")).isBetween(93500L,93600L);
        assertThatThrownBy(() -> second.check("deleted",2,"day",10,1000,reset,reset,true))
            .isInstanceOf(com.offerpilot.common.error.BusinessException.class).hasMessage("未登录");
    }
    @Test void twoInstancesShareAtomicRpmAndDailyUsage() throws Exception {
        var first = new RedisLlmUsageStore(redis);
        var second = new RedisLlmUsageStore(redis);
        Instant minuteReset = Instant.now().plusSeconds(60), dayReset = Instant.now().plusSeconds(86400);
        var executor = Executors.newFixedThreadPool(8);
        try {
            var start = new CountDownLatch(1);
            var attempts = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 30; i++) {
                var store = i % 2 == 0 ? first : second;
                attempts.add(executor.submit(() -> {
                    start.await();
                    return store.check("shared", 1, "day1", 3, 20, minuteReset, dayReset, true);
                }));
            }
            start.countDown();
            int allowed = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS) == 0) allowed++;
            assertThat(allowed).isEqualTo(3);
            assertThat(redis.getExpire("offerpilot:llm:{shared}:rpm:1")).isBetween(1L, 60L);
            first.addTokens("shared", "day1", 12, dayReset);
            second.addTokens("shared", "day1", 8, dayReset);
            assertThat(second.tokens("shared", "day1")).isEqualTo(20);
            assertThat(second.tokens("shared", "day2")).isZero();
            assertThat(second.check("shared", 2, "day1", 3, 20, minuteReset, dayReset, true)).isEqualTo(2);
            assertThat(first.check("shared", 2, "day2", 3, 20, minuteReset, dayReset, true)).isZero();
            assertThat(second.check("other", 1, "day1", 3, 20, minuteReset, dayReset, true)).isZero();
            assertThat(redis.getExpire("offerpilot:llm:{shared}:tokens:day1")).isBetween(86400L, 90000L);
        } finally { executor.shutdownNow(); }
    }
}
