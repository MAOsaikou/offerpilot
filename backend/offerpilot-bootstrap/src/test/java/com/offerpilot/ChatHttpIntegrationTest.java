package com.offerpilot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.common.error.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
@Testcontainers
class ChatHttpIntegrationTest {
    @Container
    static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Autowired ObjectMapper json;
    @Autowired GuestIdentityService identities;
    @Autowired ConversationService conversations;
    @Autowired JdbcTemplate jdbc;
    private static final AtomicReference<String> requestBody = new AtomicReference<>();
    private static final HttpServer provider = provider();
    @LocalServerPort
    private int port;

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
                        String frame = "data: {\"id\":\"chat-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + text + "\"},\"finish_reason\":null}]}\n\n";
                        out.write(frame.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    out.write(("data: {\"id\":\"chat-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":6,\"total_tokens\":18}}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
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
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.ai.deepseek.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
    }

    @AfterAll
    static void stopProvider() { provider.stop(0); }

    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (path.equals("/agent/chat")) builder.header("Accept", "text/event-stream");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String owner(String token) { return identities.require("Bearer " + token); }

    @Test
    void historySurvivesHttpRequestsAndOnlySameConversationEntersPrompt() throws Exception {
        String token = identities.create().accessToken();
        String conv = conversations.create(owner(token), null, AgentMode.AUTO).id();
        assertThat(request("POST", "/agent/chat", token, json.writeValueAsString(java.util.Map.of("conversationId", conv, "content", "我的专业是物理"))).body()).contains("run.completed");
        var second = request("POST", "/agent/chat", token, json.writeValueAsString(java.util.Map.of("conversationId", conv, "content", "适合哪些岗位？")));
        assertThat(second.body()).contains("run.completed");
        var prompt = json.readTree(requestBody.get()).path("messages");
        assertThat(prompt.size()).isEqualTo(4);
        assertThat(prompt.get(1).path("content").asText()).isEqualTo("我的专业是物理");
        assertThat(prompt.get(2).path("role").asText()).isEqualTo("assistant");
        var restored = request("GET", "/conversations/" + conv + "/messages", token, null);
        assertThat(json.readTree(restored.body()).path("data").path("items").size()).isEqualTo(4);
        request("POST", "/agent/chat", token, "{\"content\":\"新的对话\"}");
        assertThat(json.readTree(requestBody.get()).path("messages").size()).isEqualTo(2);
        assertThat(conversations.list(owner(token), 1, 20).items()).hasSize(2);
    }

    @Test
    void authenticationAndEveryConversationOperationEnforceOwnership() throws Exception {
        var credential = request("POST", "/auth/guest", null, null);
        assertThat(credential.statusCode()).isEqualTo(200);
        assertThat(credential.headers().firstValue("cache-control")).contains("no-store");
        String token = json.readTree(credential.body()).path("data").path("accessToken").asText();
        String stranger = identities.create().accessToken();
        String conv = conversations.create(owner(token), "私有会话", AgentMode.AUTO).id();
        assertThat(request("GET", "/conversations", null, null).statusCode()).isEqualTo(401);
        var unauthenticated = request("POST", "/agent/chat", null, "{\"content\":\"你好\"}");
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        assertThat(unauthenticated.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
        assertThat(json.readTree(unauthenticated.body()).path("code").asInt()).isEqualTo(20001);
        assertThat(request("POST", "/agent/chat", token, "{\"content\":\"\"}").statusCode()).isEqualTo(400);
        assertThat(request("POST", "/agent/chat", token, "{").statusCode()).isEqualTo(400);
        for (String endpoint : new String[]{"/conversations/" + conv, "/conversations/" + conv + "/messages"})
            assertThat(request("GET", endpoint, stranger, null).statusCode()).isEqualTo(404);
        assertThat(request("PATCH", "/conversations/" + conv, stranger, "{\"title\":\"盗改\"}").statusCode()).isEqualTo(404);
        assertThat(request("DELETE", "/conversations/" + conv, stranger, null).statusCode()).isEqualTo(404);
        assertThat(request("POST", "/agent/chat", stranger, json.writeValueAsString(java.util.Map.of("conversationId", conv, "content", "越权"))).statusCode()).isEqualTo(404);
        var turn = conversations.begin(owner(token), conv, "测试", null, "test");
        assertThat(request("POST", "/agent/runs/" + turn.runId() + "/cancel", stranger, null).statusCode()).isEqualTo(404);
        assertThat(conversations.list(owner(stranger), 1, 20).items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_sessions WHERE token_hash=?", Integer.class, token)).isZero();
    }

    @Test
    void titlePinCursorPaginationAndDeleteRoundTrip() throws Exception {
        String token = identities.create().accessToken(), user = owner(token);
        String conv = conversations.create(user, null, null).id();
        for (int i=0; i<18; i++) {
            var turn = conversations.begin(user, conv, "问题" + i, null, "test");
            conversations.finish(turn, "回复" + i, "COMPLETED", java.util.Map.of("promptTokens", 10), null);
        }
        var last = conversations.messages(user, conv, null, 30);
        assertThat(last.items()).hasSize(30);
        assertThat(last.hasMore()).isTrue();
        var first = conversations.messages(user, conv, last.nextBefore(), 30);
        assertThat(first.items()).hasSize(6);
        assertThat(first.items().get(0).plainText()).isEqualTo("问题0");
        assertThat(first.hasMore()).isFalse();
        assertThat(first.items()).noneMatch(m -> last.items().stream().anyMatch(n -> n.id().equals(m.id())));
        assertThat(request("PATCH", "/conversations/" + conv, token, "{\"title\":\"求职规划\",\"pinned\":true}").statusCode()).isEqualTo(200);
        assertThat(conversations.list(user, 1, 20).items().get(0).title()).isEqualTo("求职规划");
        assertThat(request("PATCH", "/conversations/" + conv, token, "{\"title\":\"  \"}").statusCode()).isEqualTo(400);
        assertThat(request("GET", "/conversations/" + conv + "/messages?size=101", token, null).statusCode()).isEqualTo(400);
        assertThat(request("DELETE", "/conversations/" + conv, token, null).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE conversation_id=?", Integer.class, conv)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE conversation_id=?", Integer.class, conv)).isZero();
    }

    @Test
    void contextContainsTenCompletePairsAndExcludesFailedAndCancelledTurns() {
        String user = owner(identities.create().accessToken());
        String conv = conversations.create(user, null, null).id();
        for (int i=0; i<12; i++) {
            var turn = conversations.begin(user, conv, "question-" + i, null, "test");
            conversations.finish(turn, "answer-" + i, "COMPLETED", null, null);
        }
        var failed = conversations.begin(user, conv, "failed-question", null, "test");
        conversations.finish(failed, "partial-answer", "FAILED", null, "test failure");
        var cancelled = conversations.begin(user, conv, "cancelled-question", null, "test");
        conversations.checkpoint(cancelled, "partial-cancelled");
        conversations.cancel(user, cancelled.runId());
        assertThat(conversations.finish(cancelled, "late completion", "COMPLETED", null, null)).isEqualTo("CANCELLED");
        var next = conversations.begin(user, conv, "next-question", null, "test");
        assertThat(next.history()).hasSize(20);
        assertThat(next.history().get(0).getText()).isEqualTo("question-2");
        assertThat(next.history()).extracting(org.springframework.ai.chat.messages.Message::getText)
                .doesNotContain("failed-question", "partial-answer", "cancelled-question", "late completion", "next-question");
        conversations.cancel(user, next.runId());
        assertThat(conversations.messages(user, conv, null, 100).items()).anyMatch(m -> m.plainText().equals("partial-cancelled") && m.status().equals("CANCELLED"));
    }

    @Test
    void contextBudgetKeepsWholePairs() {
        String user = owner(identities.create().accessToken());
        String conv = conversations.create(user, null, null).id();
        for (int i=0; i<3; i++) {
            var turn = conversations.begin(user, conv, "question" + i, null, "test");
            conversations.finish(turn, "字".repeat(10000), "COMPLETED", null, null);
        }
        var next = conversations.begin(user, conv, "next", null, "test");
        assertThat(next.history()).hasSize(4);
        assertThat(next.history().get(0).getText()).isEqualTo("question1");
    }

    @Test
    void activeRunsBlockCompetingWritesAndAbandonedRunsExpire() {
        String user = owner(identities.create().accessToken());
        String conv = conversations.create(user, null, null).id();
        var running = conversations.begin(user, conv, "question", null, "test");
        assertThatThrownBy(() -> conversations.begin(user, conv, "duplicate", null, "test")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> conversations.delete(user, conv)).isInstanceOf(BusinessException.class);
        conversations.begin(user, null, "second", null, "test");
        assertThatThrownBy(() -> conversations.begin(user, null, "third", null, "test")).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE agent_runs SET lease_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id=?", running.runId());
        assertThat(conversations.messages(user, conv, null, 30).items().get(1).status()).isEqualTo("FAILED");
        assertThat(conversations.begin(user, conv, "recovered", null, "test").history()).isEmpty();
    }

    @Test
    void concurrentRequestsOnlyStartOneTurnPerConversation() throws Exception {
        String user = owner(identities.create().accessToken());
        String conv = conversations.create(user, null, null).id();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> attempt = () -> {
            start.await();
            try { conversations.begin(user, conv, "race", null, "test"); return true; }
            catch (BusinessException expected) { return false; }
        };
        try {
            var a = executor.submit(attempt); var b = executor.submit(attempt); start.countDown();
            assertThat(java.util.List.of(a.get(10, java.util.concurrent.TimeUnit.SECONDS), b.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(conversations.messages(user, conv, null, 30).items()).hasSize(2);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void realHttpStreamsChineseAndUsageThroughActualDeepSeekClient() throws Exception {
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/agent/chat"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + identities.create().accessToken())
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"我是大三学生\",\"options\":{}}"))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("event:run.started", "event:text.delta", "你好", "，同学。",
                "event:usage", "\"promptTokens\":12", "\"completionTokens\":6", "event:run.completed", "COMPLETED");
        assertThat(requestBody.get()).contains("我是大三学生", "deepseek-flash", "\"stream\":true", "仅提供文字建议");
        assertThat(response.body().indexOf("event:usage")).isLessThan(response.body().indexOf("event:run.completed"));
    }
}
