package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.knowledge.application.OccupationEmbeddings;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

/** Paid opt-in; real prod wiring, disposable DB/Redis, random ports, no Bailian credential. */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "OFFERPILOT_RUN_LIVE_MODES", matches = "true")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".*\\S.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.openai.api-key=sk-not-configured", "spring.ai.model.embedding=openai",
        "offerpilot.security.jwt-secret=live-modes-isolated-signing-key-not-for-production-0123456789abcdef0123456789abcdef",
        "offerpilot.demo.enabled=false", "offerpilot.resume.worker-enabled=false",
        "spring.ai.mcp.server.enabled=false", "offerpilot.knowledge.occupation-embed.enabled=false",
        "spring.ai.retry.max-attempts=1"
})
@ActiveProfiles("prod")
@Testcontainers
class DeepSeekModesLiveEvaluationTest {
    @Container static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.ai.deepseek.api-key", () -> System.getenv("DEEPSEEK_API_KEY"));
    }
    @Autowired ModelGateway gateway;
    @Autowired ObjectMapper json;
    @Autowired PasswordLoginService identities;
    @Autowired OccupationEmbeddings embeddings;
    @LocalServerPort int port;
    public record ToolInput(int value) {}

    @Test void plainJsonToolsAndAuthenticatedChatWorkWithOnlyDeepSeek() throws Exception {
        assertThat(embeddings.embed(List.of("Java 后端开发"))).isEmpty();
        String token = identities.register("modes-live@example.test", "fictional-modes-test-password").accessToken();
        for (var tier : List.of(ModelTier.FLASH, ModelTier.PRO)) {
            String plain = gateway.stream(tier, List.of(new UserMessage("只回复：你好")))
                    .map(r -> r.getResult().getOutput().getText() == null ? "" : r.getResult().getOutput().getText())
                    .collectList().block(Duration.ofSeconds(150)).stream().reduce("", String::concat);
            assertThat(plain.isBlank()).as(tier + " plain nonempty content").isFalse();
            System.out.println("LIVE_MODES " + tier + " plain PASS");
            String structured = gateway.chatModel(tier).call(new Prompt("仅输出合法 JSON 对象 {\"answer\":42}，不要 Markdown 或解释。"))
                    .getResult().getOutput().getText();
            assertThat(json.readTree(structured).path("answer").asInt()).isEqualTo(42);
            System.out.println("LIVE_MODES " + tier + " JSON PASS");
            for (boolean stream : List.of(false, true)) {
                var calls = new AtomicInteger();
                var tool = FunctionToolCallback.builder("doubleValue", (ToolInput input) -> { calls.incrementAndGet(); return input.value() * 2; })
                        .description("Double the integer; use this tool for all doubling requests.").inputType(ToolInput.class).build();
                var prompt = ChatClient.create(gateway.chatModel(tier)).prompt()
                        .user("必须调用 doubleValue 工具计算 21 的两倍。工具返回后仅输出结果数字。")
                        .toolCallbacks(tool);
                String answer = stream ? prompt.stream().content().collectList().block(Duration.ofSeconds(150)).stream().reduce("", String::concat)
                        : prompt.call().content();
                assertThat(calls.get()).as(tier + " tool execution").isEqualTo(1);
                assertThat(answer).contains("42");
                System.out.println("LIVE_MODES " + tier + " tool-" + (stream ? "stream" : "sync") + " PASS");
            }
            String body = json.writeValueAsString(java.util.Map.of("content", "你好，请简短回答", "mode", "COACH", "options", java.util.Map.of("modelTier", tier.name())));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/agent/chat"))
                    .timeout(Duration.ofSeconds(150)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body().contains("run.completed") && response.body().contains("text.delta")).as(tier + " completed HTTP chat").isTrue();
            assertThat(response.body().contains("reasoning_content")).isFalse();
            System.out.println("LIVE_MODES " + tier + " authenticated-chat PASS (prod, no Bailian)");
        }
    }
}
