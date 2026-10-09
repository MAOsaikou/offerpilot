package com.offerpilot.ratelimit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Per-IP/route fixed windows. Redis operations are atomic across application instances. */
@Service
public class AuthRateLimiter {
    private static final long WINDOW_MILLIS = 60_000;
    private static final DefaultRedisScript<List> CONSUME = new DefaultRedisScript<>("""
            local used = tonumber(redis.call('GET', KEYS[1]) or '0')
            local allowed = 0
            if used < tonumber(ARGV[1]) then
                used = redis.call('INCR', KEYS[1])
                allowed = 1
            end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
                ttl = tonumber(ARGV[2])
            end
            return {allowed, used, ttl}
            """, List.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanup = new AtomicLong();

    @Autowired
    public AuthRateLimiter(ObjectProvider<StringRedisTemplate> redis) {
        this(redis.getIfAvailable(), Clock.systemUTC());
    }

    public AuthRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public Result consume(String clientIp, String bucket, int limit) {
        String key = "offerpilot:rate-limit:auth:" + bucket + ":" + clientIp;
        if (redis != null) {
            // A configured but unavailable Redis must not silently reset shared limits.
            List<?> values = redis.execute(CONSUME, List.of(key), Integer.toString(limit), Long.toString(WINDOW_MILLIS));
            if (values == null || values.size() != 3) {
                throw new IllegalStateException("Redis returned an invalid auth rate-limit result");
            }
            return new Result(((Number) values.get(0)).longValue() == 1,
                    Math.max(0, limit - ((Number) values.get(1)).intValue()),
                    seconds(((Number) values.get(2)).longValue()));
        }

        long now = clock.millis();
        long previous = lastCleanup.get();
        if (now - previous >= WINDOW_MILLIS && lastCleanup.compareAndSet(previous, now)) {
            // Lazy eviction also runs on the first request after idle; no permanent IP history.
            windows.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        }
        AtomicReference<Result> result = new AtomicReference<>();
        windows.compute(key, (ignored, current) -> {
            Window window = current == null || current.expiresAt() <= now ? new Window(0, now + WINDOW_MILLIS) : current;
            boolean allowed = window.used() < limit;
            int used = allowed ? window.used() + 1 : window.used();
            result.set(new Result(allowed, Math.max(0, limit - used), seconds(window.expiresAt() - now)));
            return new Window(used, window.expiresAt());
        });
        return result.get();
    }

    private static long seconds(long millis) {
        return Math.max(1, (millis + 999) / 1000);
    }

    public record Result(boolean allowed, int remaining, long retryAfterSeconds) {}
    private record Window(int used, long expiresAt) {}
}

