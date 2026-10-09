package com.offerpilot.llm.quota;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.env.MockEnvironment;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class LlmQuotaConfigurationTest {
    @Test void memoryFallbackIsOnlyAllowedInTestAndRedisOutageDoesNotResetQuota() {
        var configuration = new LlmQuotaConfiguration();
        var beans = new DefaultListableBeanFactory();
        var environment = new MockEnvironment();
        assertThatThrownBy(() -> configuration.llmUsageStore(beans.getBeanProvider(StringRedisTemplate.class), environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Redis");
        environment.setActiveProfiles("test");
        assertThat(configuration.llmUsageStore(beans.getBeanProvider(StringRedisTemplate.class), environment))
                .isInstanceOf(MemoryLlmUsageStore.class);
        beans.registerSingleton("redis", new StringRedisTemplate() {
            @Override public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                throw new IllegalStateException("unavailable");
            }
        });
        var store = configuration.llmUsageStore(beans.getBeanProvider(StringRedisTemplate.class), environment);
        assertThat(store).isInstanceOf(RedisLlmUsageStore.class);
        assertThat(catchThrowableOfType(() -> store.check("user", 1, "day", 1, 10, Instant.now(), Instant.now(), true), BusinessException.class).errorCode())
                .isEqualTo(ErrorCode.CACHE_UNAVAILABLE);
        assertThat(catchThrowableOfType(() -> store.addTokens("user", "day", 10, Instant.now()), BusinessException.class).errorCode())
                .isEqualTo(ErrorCode.CACHE_UNAVAILABLE);
    }
}
