package com.offerpilot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DevEmbeddingConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=dev", "BAILIAN_API_KEY=test-only");

    @Test void propertyFileToggleEnablesDevEmbedding() {
        context.withPropertyValues("SPRING_AI_MODEL_EMBEDDING=openai").run(ctx -> {
            assertThat(ctx.getEnvironment().getProperty("spring.ai.model.embedding")).isEqualTo("openai");
            assertThat(ctx.getEnvironment().getProperty("spring.ai.openai.api-key")).isEqualTo("test-only");
        });
    }

    @Test void devEmbeddingRemainsDisabledWithoutOptIn() {
        context.run(ctx -> assertThat(ctx.getEnvironment().getProperty("spring.ai.model.embedding"))
                .isEqualTo("none"));
    }
}
