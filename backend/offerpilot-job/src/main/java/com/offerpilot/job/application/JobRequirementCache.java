package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;

/** JD-only definitions; no resume evidence or model status is shared between matches. */
@Component
public class JobRequirementCache {
    private static final Duration TTL = Duration.ofDays(30);
    private static final int MAX_LOCAL = 256;
    private record Entry(JsonNode definitions, Instant expiresAt) {}
    private final LinkedHashMap<String, Entry> local = new LinkedHashMap<>(16, 0.75f, true);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    @org.springframework.beans.factory.annotation.Autowired
    public JobRequirementCache(ObjectProvider<StringRedisTemplate> redis, ObjectMapper json) {
        this(redis.getIfAvailable(), json);
    }

    JobRequirementCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    boolean persistent() { return redis != null; }

    JsonNode get(String key) {
        // Redis is authoritative across instances. Do not hide an outage behind a new extraction.
        if (redis != null) {
            try {
                String value = redis.opsForValue().get(key);
                return value == null ? null : json.readTree(value);
            } catch (Exception error) { throw unavailable(); }
        }
        synchronized (local) {
            Entry entry = local.get(key);
            if (entry == null) return null;
            if (entry.expiresAt().isBefore(Instant.now())) { local.remove(key); return null; }
            return entry.definitions().deepCopy();
        }
    }

    JsonNode putIfAbsent(String key, JsonNode definitions) {
        if (redis != null) {
            try {
                redis.opsForValue().setIfAbsent(key, definitions.toString(), TTL);
                // Concurrent cold extractions all use the first committed definition list.
                JsonNode winner = get(key);
                if (winner == null) throw unavailable();
                return winner;
            } catch (BusinessException error) { throw error; }
            catch (Exception error) { throw unavailable(); }
        }
        synchronized (local) {
            JsonNode winner = get(key);
            if (winner != null) return winner;
            local.put(key, new Entry(definitions.deepCopy(), Instant.now().plus(TTL)));
            while (local.size() > MAX_LOCAL) local.remove(local.keySet().iterator().next());
            return definitions.deepCopy();
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.CACHE_UNAVAILABLE, "岗位要求缓存暂不可用，请稍后重试");
    }
}
