package com.offerpilot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 HTTP → Controller → Spring AI DeepSeek 适配器 → 本地供应商桩 → SSE。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=deepseek", "spring.ai.model.embedding=none",
        "spring.ai.retry.max-attempts=1", "spring.ai.deepseek.api-key=test-only-key",
        "offerpilot.llm.default-tier=CHEAP", "spring.ai.mcp.server.enabled=false"
})
@ActiveProfiles("test")
class ChatHttpIntegrationTest {
    private static final AtomicReference<String> requestBody = new AtomicReference<>();
    private static final HttpServer provider = provider();
    @LocalServerPort private int port;

    private static HttpServer provider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requestBody.set(body);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    for (String text : new String[]{"你好", "，同学。"}) {
                        String frame = "data: {\"id\":\"chat-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + text + "\"},\"finish_reason\":null}]}\n\n";
                        out.write(frame.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    out.write(("data: {\"id\":\"chat-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":6,\"total_tokens\":18}}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.deepseek.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
    }

    @AfterAll
    static void stopProvider() { provider.stop(0); }

    @Test
    void realHttpStreamsChineseAndUsageThroughActualDeepSeekClient() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/agent/chat"))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"我是大三学生\",\"options\":{}}"))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("event:run.started", "event:text.delta", "你好", "，同学。",
                    "event:usage", "\"promptTokens\":12", "\"completionTokens\":6", "event:run.completed", "COMPLETED");
            assertThat(requestBody.get()).contains("我是大三学生", "deepseek-v4-flash", "\"stream\":true", "仅提供文字建议");
            assertThat(response.body().indexOf("event:usage")).isLessThan(response.body().indexOf("event:run.completed"));
        }
    }
}
