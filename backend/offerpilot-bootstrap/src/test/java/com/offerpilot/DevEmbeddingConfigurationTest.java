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
    @Test void productionEmbeddingDefaultsToIndependentLocalOpenAiCompatibleRoute() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod", "BAILIAN_API_KEY=fictional-bailian-key")
                .run(ctx -> {
                    var env = ctx.getEnvironment();
                    assertThat(env.getProperty("spring.ai.openai.embedding.base-url")).isEqualTo("http://localhost:11434");
                    assertThat(env.getProperty("spring.ai.openai.embedding.api-key")).isEqualTo("ollama");
                    assertThat(env.getProperty("spring.ai.openai.embedding.embeddings-path")).isEqualTo("/v1/embeddings");
                    assertThat(env.getProperty("spring.ai.openai.embedding.options.model")).isEqualTo("bge-m3");
                    assertThat(env.getProperty("spring.ai.openai.embedding.options.dimensions")).isEqualTo("1024");
                });
    }

}
