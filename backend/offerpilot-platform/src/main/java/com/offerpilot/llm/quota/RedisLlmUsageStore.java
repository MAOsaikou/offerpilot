package com.offerpilot.llm.quota;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.time.Instant;
import java.util.List;

public final class RedisLlmUsageStore implements LlmUsageStore {
    private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[3]) == 1 then return 3 end
            local tokens = tonumber(redis.call('GET', KEYS[2]) or '0')
            if tokens >= tonumber(ARGV[2]) then return 2 end
            local requests = tonumber(redis.call('GET', KEYS[1]) or '0')
            if requests >= tonumber(ARGV[1]) then return 1 end
            if ARGV[4] == 'true' then
              redis.call('INCR', KEYS[1])
              redis.call('EXPIREAT', KEYS[1], ARGV[3])
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> ADD = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
            local value = redis.call('INCRBY', KEYS[1], ARGV[1])
            redis.call('EXPIREAT', KEYS[1], ARGV[2])
            return value
            """, Long.class);
    private final StringRedisTemplate redis;
    public RedisLlmUsageStore(StringRedisTemplate redis) { this.redis = redis; }
    // Both keys share a Redis Cluster hash slot; separate namespace from authentication limits.
    private String prefix(String owner) { return "offerpilot:llm:{" + owner + "}:"; }
    @Override public int check(String owner, long minute, String day, int rpm, long dailyLimit,
            Instant minuteReset, Instant dayReset, boolean consume) {
        try {
            Long result = redis.execute(CHECK, List.of(prefix(owner) + "rpm:" + minute, prefix(owner) + "tokens:" + day, prefix(owner) + "revoked"),
                    String.valueOf(rpm), String.valueOf(dailyLimit), String.valueOf(minuteReset.getEpochSecond()), String.valueOf(consume));
            if (result == null) throw new IllegalStateException("Missing quota result");
            if (result == 3) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            return result.intValue();
        } catch (BusinessException ex) { throw ex; }
        catch (RuntimeException ex) { throw new BusinessException(ErrorCode.CACHE_UNAVAILABLE); }
    }
    @Override public void erase(String owner) {
        // Fence late accounting/admissions before removing counters; expires after all daily counters/runs.
        redis.opsForValue().set(prefix(owner) + "revoked", "1", java.time.Duration.ofHours(26));
        var options = org.springframework.data.redis.core.ScanOptions.scanOptions().match(prefix(owner) + "*").count(100).build();
        try (var cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (!key.equals(prefix(owner) + "revoked")) redis.delete(key);
            }
        }
    }
    @Override public void addTokens(String owner, String day, long tokens, Instant dayReset) {
        try {
            if (redis.execute(ADD, List.of(prefix(owner) + "tokens:" + day, prefix(owner) + "revoked"), String.valueOf(tokens),
                    String.valueOf(dayReset.plusSeconds(3600).getEpochSecond())) == null)
                throw new IllegalStateException("Missing usage result");
        } catch (BusinessException ex) { throw ex; }
        catch (RuntimeException ex) { throw new BusinessException(ErrorCode.CACHE_UNAVAILABLE); }
    }
}
