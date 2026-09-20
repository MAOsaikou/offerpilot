package com.offerpilot.llm;

import com.offerpilot.config.OfferPilotProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.MimeTypeUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ModelGatewayTest {
    ModelGateway gateway(String provider, boolean vision, MockEnvironment env) {
        var tier = new OfferPilotProperties.Llm.Tier(provider, "my-model", Duration.ofSeconds(5), vision);
        var properties = new OfferPilotProperties(null, null, null, null, null,
                new OfferPilotProperties.Llm("CHEAP", Map.of("CHEAP", tier), Map.of()), null);
        var beans = new DefaultListableBeanFactory();
        return new ModelGateway(properties, beans.getBeanProvider(DeepSeekChatModel.class), beans.getBeanProvider(OpenAiChatModel.class), env);
    }
    @Test void customCompatibleProviderReceivesItsOwnKeyModelPathAndImages() throws Exception {
        var body = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom/chat", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write("data: {\"id\":\"test\",\"model\":\"my-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            var env = new MockEnvironment().withProperty("offerpilot.llm.providers.custom.base-url", "http://127.0.0.1:" + server.getAddress().getPort())
                    .withProperty("offerpilot.llm.providers.custom.api-key", "test-custom-key")
                    .withProperty("offerpilot.llm.providers.custom.completions-path", "/custom/chat")
                    .withProperty("spring.ai.deepseek.api-key", "must-not-use-deepseek-key");
            var gateway = gateway("custom", true, env);
            for (boolean image : List.of(false, true)) {
                var user = UserMessage.builder().text("hello").media(image ? List.of(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[]{1, 2, 3}))) : List.of()).build();
                assertThat(gateway.stream(ModelTier.CHEAP, List.of(user)).collectList().block(Duration.ofSeconds(5))).isNotEmpty();
                assertThat(authorization.get()).isEqualTo("Bearer test-custom-key");
                assertThat(body.get()).contains("my-model").doesNotContain("must-not-use-deepseek-key");
                if (image) assertThat(body.get()).contains("image_url", "data:image/png;base64,");
            }
            assertThat(gateway.catalog().get(0).configured()).isTrue();
            assertThatThrownBy(() -> gateway("custom", false, env).requireAvailable(ModelTier.CHEAP, true)).hasMessageContaining("图片能力");
        } finally { server.stop(0); }
    }
    @Test void unknownProviderNeverBorrowsAnotherProvidersCredentials() {
        var gateway = gateway("unknown", true, new MockEnvironment().withProperty("spring.ai.openai.api-key", "valid-but-wrong-key"));
        assertThat(gateway.catalog().get(0).configured()).isFalse();
        assertThatThrownBy(() -> gateway.requireAvailable(ModelTier.CHEAP, false)).hasMessageContaining("配置完整");
    }
}
