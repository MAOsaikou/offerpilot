package com.offerpilot.llm.quota;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Clock;

@Configuration
public class LlmQuotaConfiguration {
    @Bean LlmUsageStore llmUsageStore(ObjectProvider<StringRedisTemplate> redis, Environment environment) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template != null) return new RedisLlmUsageStore(template);
        if (environment.acceptsProfiles(Profiles.of("test"))) return new MemoryLlmUsageStore(Clock.systemUTC());
        throw new IllegalStateException("LLM quotas require Redis outside the test profile");
    }
}
