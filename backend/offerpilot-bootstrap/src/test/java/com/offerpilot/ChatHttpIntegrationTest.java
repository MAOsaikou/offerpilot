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
import com.offerpilot.conversation.application.MessageCard;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.resume.application.ResumeOptimizationForChat;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        "offerpilot.llm.default-tier=FLASH", "spring.ai.mcp.server.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class ChatHttpIntegrationTest {
    @Container
    static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @MockBean ResumeOptimizationForChat optimizations;
    @MockBean com.offerpilot.resume.application.ResumeObjectStore objects;
    private final java.util.Map<String, byte[]> stored = new java.util.concurrent.ConcurrentHashMap<>();
    @Autowired ObjectMapper json;
    @Autowired GuestIdentityService identities;
    @Autowired ConversationService conversations;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.offerpilot.llm.quota.LlmQuotaLimiter quotas;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private static final AtomicReference<String> requestBody = new AtomicReference<>();
    private static final HttpServer provider = provider();
    @Autowired com.offerpilot.resume.application.ResumePatchService patchService;
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
                    boolean planning = body.contains("意图识别与规划 v1");
                    boolean jobAction = body.contains("岗位动作与搜索参数解析 v1");
                    boolean resumeAction = body.contains("简历动作解析 v1");
                    String decision = body.contains("看板审批验收") ? (planning
                            ? "{\"kind\":\"single\",\"expert\":\"JOB\",\"confidence\":1}"
                            : body.contains("新建") ? "{\"action\":\"createApplication\",\"status\":\"TODO\"}"
                            : body.contains("非法状态") ? "{\"action\":\"updateApplicationStatus\",\"status\":\"WRITTEN_TEST\"}"
                            : "{\"action\":\"updateApplicationStatus\",\"status\":\"INTERVIEW\"}") : body.contains("改写刷新验收") ? (planning
                            ? "{\"kind\":\"single\",\"expert\":\"RESUME\",\"confidence\":1}"
                            : "{\"action\":\"optimize\",\"question\":\"\"}") : body.contains("面试评分验收") ? "{\"kind\":\"single\",\"expert\":\"INTERVIEWER\",\"confidence\":1}" : body.contains("职业推荐验收")
                            ? "{\"kind\":\"single\",\"expert\":\"CAREER_DISCOVERY\",\"confidence\":1}"
                            : body.contains("复合验收") ? """
                            {"kind":"composite","confidence":1,"plan":{"goal":"岗位与简历准备","steps":[
                            {"id":"s1","title":"职业探索","agent":"CAREER_DISCOVERY","inputs":{},"dependsOn":[]},
                            {"id":"s2","title":"成长指导","agent":"COACH","inputs":{"jobFrom":"s1"},"dependsOn":["s1"]}]}}
                            """ : "{\"kind\":\"chitchat\",\"confidence\":1}";
                    for (String text : planning || resumeAction || jobAction ? new String[]{decision} : new String[]{"你好", "，同学。"}) {
                        String frame = "data: {\"id\":\"chat-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":" + new ObjectMapper().writeValueAsString(text) + "},\"finish_reason\":null}]}\n\n";
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

    private HttpResponse<String> upload(String token, String filename, byte[] bytes) throws Exception {
        String boundary = "op" + java.util.UUID.randomUUID();
        var body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/agent/attachments"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void chatImageAttachmentReachesTheVisionModelStaysPrivateAndIsReleasedWithTheConversation() throws Exception {
        org.mockito.Mockito.when(objects.bucket()).thenReturn("test");
        org.mockito.Mockito.doAnswer(call -> stored.put(call.getArgument(0), call.getArgument(1))).when(objects).put(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.when(objects.get(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> stored.get(call.<String>getArgument(1)));
        String token = identities.create().accessToken(), stranger = identities.create().accessToken(), user = owner(token);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

        assertThat(upload(token, "notes.txt", "hello".getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(400);
        var uploaded = upload(token, "jd.png", png);
        assertThat(uploaded.statusCode()).isEqualTo(200);
        String fileId = json.readTree(uploaded.body()).path("data").path("fileId").asText();
        assertThat(request("GET", "/agent/attachments/" + fileId, stranger, null).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/agent/attachments/" + fileId, token, null).statusCode()).isEqualTo(200);

        String chat = json.writeValueAsString(java.util.Map.of("content", "帮我看看这张岗位截图", "mode", "COACH",
                "attachments", java.util.List.of(java.util.Map.of("fileId", fileId, "kind", "image"))));
        // Someone else's file id cannot be attached.
        assertThat(request("POST", "/agent/chat", stranger, chat).statusCode()).isEqualTo(400);
        var response = request("POST", "/agent/chat", token, chat);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("run.completed");
        assertThat(requestBody.get()).contains("data:image/png;base64,");

        String conversation = jdbc.queryForObject("SELECT conversation_id FROM agent_runs WHERE user_id=?", String.class, user);
        var parts = json.readTree(jdbc.queryForObject("SELECT parts::text FROM messages WHERE conversation_id=? AND role='USER'", String.class, conversation));
        assertThat(parts.get(1).path("type").asText()).isEqualTo("image");
        assertThat(parts.get(1).path("fileId").asText()).isEqualTo(fileId);

        assertThat(request("DELETE", "/conversations/" + conversation, token, null).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE id=?", Integer.class, fileId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_data_cleanup WHERE object_key=?", Integer.class, "chat/" + user + "/" + fileId)).isEqualTo(1);
    }

    @Test
    void legacyAndUnknownChatTiersUseFlashInsteadOfReturning400() throws Exception {
        for (String tier : java.util.List.of("CHEAP", "economy", "经济", "balanced", "unknown")) {
            String token = identities.create().accessToken();
            var response = request("POST", "/agent/chat", token,
                    json.writeValueAsString(java.util.Map.of("content", "hello", "mode", "COACH", "options", java.util.Map.of("modelTier", tier))));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("run.completed", "deepseek-flash");
            assertThat(json.readTree(requestBody.get()).path("model").asText()).isEqualTo("deepseek-flash");
        }
    }

    @Test
    void exhaustedGuestQuotaReturnsHttp429BeforeSseOrRunCreation() throws Exception {
        String token = identities.create().accessToken(), user = owner(token);
        var admission = quotas.check(user, com.offerpilot.llm.ModelTier.FLASH, true);
        quotas.record(admission, 50000);
        var response = request("POST", "/agent/chat", token, "{\"content\":\"hello\"}");
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(40008);
        assertThat(response.headers().firstValue("Retry-After")).hasValueSatisfying(value -> assertThat(Long.parseLong(value)).isBetween(1L, 86400L));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE user_id=?", Integer.class, user)).isZero();
    }

    @Test
    void guestRpmAndProPolicyAreOrdinaryHttpErrors() throws Exception {
        String token = identities.create().accessToken(), user = owner(token);
        var forbidden = request("POST", "/agent/chat", token, "{\"content\":\"hello\",\"options\":{\"modelTier\":\"PRO\"}}");
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(json.readTree(forbidden.body()).path("code").asInt()).isEqualTo(20004);
        for (int i = 0; i < 20; i++) quotas.check(user, com.offerpilot.llm.ModelTier.FLASH, true);
        var limited = request("POST", "/agent/chat", token, "{\"content\":\"hello\"}");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(json.readTree(limited.body()).path("code").asInt()).isEqualTo(40011);
        assertThat(limited.headers().firstValue("Retry-After")).hasValueSatisfying(value -> assertThat(Long.parseLong(value)).isBetween(1L, 60L));
    }

    @Test
    void passwordValidationEnforcesUtf8LimitWithoutTruncation() throws Exception {
        String email = "password-boundary-" + java.util.UUID.randomUUID() + "@example.com";
        for (String invalid : new String[]{"      ", "\u2003".repeat(6), "汉".repeat(24) + "a", "😀".repeat(19)}) {
            var response = request("POST", "/auth/register", null,
                    json.writeValueAsString(java.util.Map.of("email", email, "password", invalid)));
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(10001);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=?", Integer.class, email)).isZero();
        String password = "汉".repeat(24);
        String payload = json.writeValueAsString(java.util.Map.of("email", email, "password", password));
        assertThat(request("POST", "/auth/register", null, payload).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/auth/login", null, payload).statusCode()).isEqualTo(200);
        for (String endpoint : new String[]{"/auth/login", "/auth/staff/login"}) {
            assertThat(request("POST", endpoint, null, json.writeValueAsString(
                    java.util.Map.of("email", email, "password", password + "different"))).statusCode()).isEqualTo(400);
        }
    }

    @Test
    void staffAdministrationEnforcesRolesAndControlsExistingCredentials() throws Exception {
        String staffId = com.offerpilot.common.id.IdGenerator.next("usr");
        String staffEmail = "admin-" + java.util.UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO users(id,email,password_hash,nickname,roles) VALUES (?,?,?,?,'{STAFF}')",
                staffId, staffEmail, passwordEncoder.encode("isolated-admin-password"), "Test admin");
        var staffLogin = request("POST", "/auth/staff/login", null,
                json.writeValueAsString(java.util.Map.of("email", staffEmail, "password", "isolated-admin-password")));
        String staff = json.readTree(staffLogin.body()).path("data").path("accessToken").asText();
        String email = "managed-" + java.util.UUID.randomUUID() + "@example.com";
        var registered = request("POST", "/auth/register", null,
                json.writeValueAsString(java.util.Map.of("email", email, "password", "isolated-user-password")));
        var user = json.readTree(registered.body()).path("data");
        String userId = user.path("user").path("id").asText();
        String token = user.path("accessToken").asText();
        String guest = identities.create().accessToken();
        String guestId = owner(guest);
        assertThat(request("GET", "/staff/users", null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/staff/users", token, null).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/staff/users", guest, null).statusCode()).isEqualTo(401);
        assertThat(request("PATCH", "/staff/users/" + userId + "/status", token,
                "{\"status\":\"DISABLED\"}").statusCode()).isEqualTo(403);
        var list = request("GET", "/staff/users?size=100", staff, null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.headers().firstValue("cache-control")).contains("no-store");
        assertThat(json.readTree(list.body()).path("data").path("items").findValuesAsText("id"))
                .contains(userId, guestId).doesNotContain(staffId);
        assertThat(request("PATCH", "/staff/users/" + staffId + "/status", staff,
                "{\"status\":\"DISABLED\"}").statusCode()).isEqualTo(403);
        assertThat(request("PATCH", "/staff/users/missing/status", staff,
                "{\"status\":\"DISABLED\"}").statusCode()).isEqualTo(404);
        assertThat(request("PATCH", "/staff/users/" + userId + "/status", staff,
                "{\"status\":\"DELETED\"}").statusCode()).isEqualTo(400);
        for (String target : new String[]{userId, guestId}) {
            assertThat(request("PATCH", "/staff/users/" + target + "/status", staff,
                    "{\"status\":\"DISABLED\"}").statusCode()).isEqualTo(200);
        }
        assertThat(request("GET", "/conversations", token, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/conversations", guest, null).statusCode()).isEqualTo(401);
        for (String target : new String[]{userId, guestId}) {
            assertThat(request("PATCH", "/staff/users/" + target + "/status", staff,
                    "{\"status\":\"ACTIVE\"}").statusCode()).isEqualTo(200);
        }
        assertThat(request("GET", "/conversations", token, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/conversations", guest, null).statusCode()).isEqualTo(200);
        assertThat(request("PATCH", "/staff/users/" + userId + "/status", staff,
                "{\"status\":\"ACTIVE\"}").statusCode()).isEqualTo(400);
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", staffId);
        assertThat(request("GET", "/staff/users", staff, null).statusCode()).isEqualTo(401);
    }

    @Test
    void passwordAuthenticationAndGuestOwnershipRemainIsolated() throws Exception {
        String email = "auth-" + java.util.UUID.randomUUID() + "@example.com";
        String payload = json.writeValueAsString(java.util.Map.of("email", email, "password", "integration-only-password"));
        var registered = request("POST", "/auth/register", null, payload);
        assertThat(registered.statusCode()).isEqualTo(200);
        assertThat(registered.headers().firstValue("cache-control")).contains("no-store");
        var data = json.readTree(registered.body()).path("data");
        String token = data.path("accessToken").asText();
        String userId = data.path("user").path("id").asText();
        assertThat(data.path("expiresIn").asLong()).isEqualTo(900);
        assertThat(request("POST", "/auth/register", null, payload).statusCode()).isEqualTo(409);
        assertThat(request("POST", "/auth/login", null, payload).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/auth/staff/login", null, payload).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/auth/login", null,
                json.writeValueAsString(java.util.Map.of("email", email, "password", "incorrect"))).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/auth/verify", token, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/auth/verify", null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/auth/verify", token + "tampered", null).statusCode()).isEqualTo(401);

        String conv = conversations.create(userId, "JWT 私有会话", null).id();
        String guest = identities.create().accessToken();
        assertThat(request("GET", "/conversations/" + conv, token, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/conversations/" + conv, guest, null).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", userId);
        assertThat(request("GET", "/auth/verify", token, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/conversations/" + conv, token, null).statusCode()).isEqualTo(401);
    }

    @Test
    void publicSeedAccountsAreDisabledButAnIsolatedStaffFixtureCanLogin() throws Exception {
        assertThat(jdbc.queryForList("SELECT status FROM users WHERE id IN ('usr_test_user001','usr_test_staff001')", String.class))
                .containsExactlyInAnyOrder("DISABLED", "DISABLED");
        String staffId = com.offerpilot.common.id.IdGenerator.next("usr");
        String email = "staff-" + java.util.UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO users(id,email,password_hash,nickname,roles) VALUES (?,?,?,?,'{STAFF}')",
                staffId, email, passwordEncoder.encode("isolated-staff-password"), "Test staff");
        String payload = json.writeValueAsString(java.util.Map.of("email", email, "password", "isolated-staff-password"));
        var response = request("POST", "/auth/staff/login", null, payload);
        assertThat(response.statusCode()).isEqualTo(200);
        var result = json.readTree(response.body()).path("data");
        assertThat(result.path("user").path("roles").get(0).asText()).isEqualTo("STAFF");
        assertThat(request("POST", "/auth/login", null, payload).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/auth/verify", result.path("accessToken").asText(), null).statusCode()).isEqualTo(200);
    }

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
    void compositeProducesRealSseAndPersistsFinalText() throws Exception {
        String token = identities.create().accessToken();
        var response = request("POST", "/agent/chat", token, "{\"content\":\"复合验收：分析岗位并指导简历\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        var events = response.body().lines().filter(line -> line.startsWith("data:"))
                .map(line -> {
                    try { return json.readTree(line.substring(5).trim()); }
                    catch (IOException ex) { throw new RuntimeException(ex); }
                }).toList();
        assertThat(events.stream().map(e -> e.path("type").asText()).toList()).containsExactly(
                "run.started", "plan.created", "step.updated", "tool.call", "tool.result", "step.updated",
                "step.updated", "tool.call", "tool.result", "step.updated", "text.delta", "text.delta", "usage", "run.completed");
        assertThat(events.stream().map(e -> e.path("id").asInt()).toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, events.size()).boxed().toList());
        assertThat(response.body()).contains("你好", "，同学。", "event:usage", "\"promptTokens\":24", "\"completionTokens\":12");
        assertThat(response.body().indexOf("event:usage")).isLessThan(response.body().indexOf("event:run.completed"));
        String conv = events.get(0).path("conversationId").asText();
        var messages = conversations.messages(owner(token), conv, null, 30).items();
        assertThat(messages.get(1).plainText()).isEqualTo("你好，同学。");
        assertThat(messages.get(1).status()).isEqualTo("COMPLETED");
        assertThat(messages.get(1).parts()).hasSize(1);
        assertThat(messages.get(1).parts().get(0).path("type").asText()).isEqualTo("text");
        assertThat(requestBody.get()).contains("成长摘要", "设为目标").doesNotContain("未执行真实业务操作");
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "orchestrator-sse.json"), json.writeValueAsString(events));
    }

    @Autowired com.offerpilot.board.application.ApplicationService applications;

    @Test
    void interviewChatWritesRequireApprovalForStartAnswerAndCompletion() throws Exception {
        String token = identities.create().accessToken(), stranger = identities.create().accessToken();
        String id = null;
        for (String action : java.util.List.of("startInterview", "continueInterview", "completeInterview")) {
            for (String decision : java.util.List.of("REJECT", "APPROVE")) {
                var context = new java.util.LinkedHashMap<String, Object>();
                context.put("interviewAction", action); context.put("role", "Java工程师");
                if (id != null) context.put("interviewSessionId", id);
                String before = request("GET", "/interviews", token, null).body();
                var beforeData = json.readTree(before).path("data");
                var beforeSession = id == null ? null : json.readTree(request("GET", "/interviews/" + id, token, null).body()).path("data");
                var body = json.writeValueAsString(java.util.Map.of("content", action.equals("continueInterview") ? "我负责接口" : "模拟面试",
                        "options", java.util.Map.of("context", context)));
                var chat = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/agent/chat"))
                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var response = HttpClient.newHttpClient().send(chat, HttpResponse.BodyHandlers.ofInputStream());
                assertThat(response.statusCode()).isEqualTo(200);
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    com.fasterxml.jackson.databind.JsonNode approval = null;
                    for (String line; (line = reader.readLine()) != null;) {
                        if (!line.startsWith("data:")) continue;
                        var event = json.readTree(line.substring(5).trim());
                        if (event.path("type").asText().equals("approval.required")) { approval = event; break; }
                    }
                    assertThat(approval).isNotNull();
                    assertThat(approval.path("name").asText()).isEqualTo(action);
                    assertThat(approval.path("editableFields")).isEmpty();
                    assertThat(json.readTree(request("GET", "/interviews", token, null).body()).path("data")).isEqualTo(beforeData);
                    String path = "/agent/runs/" + approval.path("runId").asText() + "/approvals/" + approval.path("approvalId").asText();
                    assertThat(request("POST", path, stranger, "{\"decision\":\"APPROVE\"}").statusCode()).isEqualTo(404);
                    assertThat(request("POST", path, token, "{\"decision\":\"APPROVE\",\"args\":{\"sessionId\":\"iv_other\"}}").statusCode()).isEqualTo(400);
                    assertThat(request("POST", path, token, json.writeValueAsString(java.util.Map.of("decision", decision))).statusCode()).isEqualTo(200);
                    var rest = reader.lines().filter(line -> line.startsWith("data:")).map(line -> {
                        try { return json.readTree(line.substring(5).trim()); }
                        catch (IOException ex) { throw new IllegalStateException(ex); }
                    }).toList();
                    var result = rest.stream().filter(e -> e.path("type").asText().equals("tool.result")).findFirst().orElseThrow();
                    assertThat(result.path("ok").asBoolean()).isEqualTo(decision.equals("APPROVE"));
                    assertThat(rest.toString()).doesNotContain("占位专家");
                    if (decision.equals("REJECT")) {
                        assertThat(json.readTree(request("GET", "/interviews", token, null).body()).path("data")).isEqualTo(beforeData);
                        if (id != null) assertThat(json.readTree(request("GET", "/interviews/" + id, token, null).body()).path("data")).isEqualTo(beforeSession);
                    } else {
                        var card = rest.stream().filter(e -> e.path("type").asText().equals("ui.card")).findFirst().orElseThrow();
                        if (action.equals("startInterview")) {
                            assertThat(card.path("cardType").asText()).isEqualTo("interviewer_state");
                            id = card.path("payload").path("sessionId").asText();
                        } else if (action.equals("continueInterview")) {
                            assertThat(card.path("payload").path("state").asText()).isEqualTo("ASKING");
                            assertThat(json.readTree(request("GET", "/interviews/" + id, token, null).body()).path("data").path("answers").get(0).path("answerText").asText()).isEqualTo("我负责接口");
                        } else {
                            assertThat(card.path("cardType").asText()).isEqualTo("interview_report");
                            assertThat(json.readTree(request("GET", "/interviews/" + id, token, null).body()).path("data").path("completed").asBoolean()).isTrue();
                        }
                    }
                }
            }
        }
    }

    @Test
    void interviewScoreUsesExistingSessionAndPersistsTextRubricWithoutMutatingOrLeakingOwner() throws Exception {
        String token = identities.create().accessToken(), stranger = identities.create().accessToken();
        var created = request("POST", "/interviews", token, "{\"role\":\"Java工程师\",\"kind\":\"项目与经历\"}");
        String id = json.readTree(created.body()).path("data").path("id").asText();
        assertThat(request("PUT", "/interviews/" + id + "/answers/0", token,
                "{\"answerText\":\"当时在项目中，我负责Java工程师接口目标。我实现缓存，最终降低延迟20%。\"}").statusCode()).isEqualTo(200);
        var story = request("POST", "/star-stories", token, json.writeValueAsString(java.util.Map.of("sessionId", id, "questionIndex", 0)));
        assertThat(story.statusCode()).isEqualTo(200);
        var response = request("POST", "/agent/chat", token, "{\"content\":\"面试评分验收：评价刚才的面试\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        var events = response.body().lines().filter(line -> line.startsWith("data:")).map(line -> {
            try { return json.readTree(line.substring(5).trim()); }
            catch (IOException ex) { throw new IllegalStateException(ex); }
        }).toList();
        var card = events.stream().filter(e -> e.path("type").asText().equals("ui.card")).findFirst().orElseThrow();
        assertThat(card.path("cardType").asText()).isEqualTo("interview_report");
        var payload = card.path("payload");
        assertThat(payload.path("id").asText()).isEqualTo(id);
        assertThat(payload.path("dimensions").has("professional")).isTrue();
        assertThat(payload.path("dimensions").has("presence")).isFalse();
        assertThat(payload.path("turns")).hasSize(5);
        assertThat(payload.path("turns").get(0).path("star").path("action").asBoolean()).isTrue();
        assertThat(response.body()).doesNotContain("占位专家", "placeholder.INTERVIEWER", "approval.required", "Report[", "Dimensions[");
        var citations = events.stream().filter(e -> e.path("cardType").asText().equals("star_story_citations")).findFirst().orElseThrow();
        assertThat(citations.path("payload").path("items").get(0).path("id"))
                .isEqualTo(json.readTree(story.body()).path("data").path("id"));
        var saved = conversations.messages(owner(token), events.get(0).path("conversationId").asText(), null, 30).items().get(1);
        assertThat(saved.parts().get(1).path("payload")).isEqualTo(payload);
        assertThat(json.readTree(request("GET", "/interviews/" + id, token, null).body()).path("data").path("completed").asBoolean()).isFalse();
        request("PATCH", "/interviews/" + id, token, "{\"completed\":true}");
        var completedSnapshot = json.readTree(request("GET", "/interviews/" + id, token, null).body()).path("data");
        for (String text : java.util.List.of("给我这场面试的评分。", "帮我打分", "给我分数", "评价一下我的表现")) {
            var natural = request("POST", "/agent/chat", token, json.writeValueAsString(java.util.Map.of(
                    "content", text, "mode", "INTERVIEWER", "options", java.util.Map.of("context", java.util.Map.of("interviewSessionId", id)))));
            assertThat(sseEvents(natural.body()).stream().filter(e -> e.path("type").asText().equals("ui.card")))
                    .extracting(e -> e.path("cardType").asText()).contains("interview_report");
            assertThat(natural.body()).doesNotContain("approval.required", "面试练习未完成", "event:error");
        }
        var answerAfterCompletion = request("POST", "/agent/chat", token, json.writeValueAsString(java.util.Map.of(
                "content", "我补充项目验证方式", "mode", "INTERVIEWER", "options", java.util.Map.of("context", java.util.Map.of("interviewSessionId", id)))));
        assertThat(answerAfterCompletion.body()).contains("run.completed", "这场面试已完成", "本轮没有保存或推进答案")
                .doesNotContain("approval.required", "面试练习未完成", "event:error");
        assertThat(json.readTree(request("GET", "/interviews/" + id, token, null).body()).path("data")).isEqualTo(completedSnapshot);
        var other = request("POST", "/agent/chat", stranger, json.writeValueAsString(java.util.Map.of("content", "给我面试反馈", "options",
                java.util.Map.of("context", java.util.Map.of("interviewAction", "scoreInterview", "interviewSessionId", id)))));
        assertThat(other.body()).doesNotContain("interview_report", "当时在项目中");
        assertThat(other.body()).contains("面试");
    }

    @Test
    void careerChatRecommendationUsesSavedInterestsAndPersistsCardForRefresh() throws Exception {
        String token = identities.create().accessToken();
        var interests = java.util.List.of("技术研发", "数据分析");
        assertThat(request("PUT", "/career/profile", token, json.writeValueAsString(java.util.Map.of(
                "target", "原目标", "city", "上海", "skills", "Java", "interests", interests))).statusCode()).isEqualTo(200);
        var response = request("POST", "/agent/chat", token, "{\"content\":\"职业推荐验收：我不知道适合什么\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        var events = response.body().lines().filter(line -> line.startsWith("data:")).map(line -> {
            try { return json.readTree(line.substring(5).trim()); }
            catch (IOException ex) { throw new IllegalStateException(ex); }
        }).toList();
        var card = events.stream().filter(e -> e.path("type").asText().equals("ui.card")).findFirst().orElseThrow();
        assertThat(card.path("cardType").asText()).isEqualTo("career_recommendation");
        assertThat(card.path("payload").path("hollandCode").asText()).isEqualTo("IRC");
        assertThat(card.path("payload").path("interestsUsed")).isEqualTo(json.valueToTree(interests));
        assertThat(card.path("payload").path("recommendations")).hasSize(8);
        card.path("payload").path("recommendations").forEach(item -> {
            assertThat(item.path("reasons")).hasSize(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM occupations WHERE id=? AND name=?", Integer.class,
                    item.path("occupationId").asText(), item.path("name").asText())).isEqualTo(1);
        });
        assertThat(response.body()).doesNotContain("占位专家", "placeholder.CAREER");
        var saved = conversations.messages(owner(token), events.get(0).path("conversationId").asText(), null, 30).items().get(1);
        assertThat(saved.parts().get(1).path("payload")).isEqualTo(card.path("payload"));
        assertThat(json.readTree(request("GET", "/career/profile", token, null).body()).path("data").path("target").asText()).isEqualTo("原目标");
    }

    @Test
    void careerTargetApprovalWritesProfileOnlyAfterApprovalAndRejectPreservesItWithOwnerIsolation() throws Exception {
        String token = identities.create().accessToken(), stranger = identities.create().accessToken();
        String occupationId = "occ_backend_dev";
        String occupationName = jdbc.queryForObject("SELECT name FROM occupations WHERE id=?", String.class, occupationId);
        var initial = java.util.Map.of("target", "原目标", "city", "上海", "skills", "Java",
                "interests", java.util.List.of("技术研发", "数据分析"));
        assertThat(request("PUT", "/career/profile", token, json.writeValueAsString(initial)).statusCode()).isEqualTo(200);
        assertThat(request("PUT", "/career/tasks", token, json.writeValueAsString(java.util.Map.of("completed",
                java.util.List.of(com.offerpilot.career.application.GrowthTaskCatalog.TITLES.get(0))))).statusCode()).isEqualTo(200);
        for (String decision : java.util.List.of("REJECT", "APPROVE")) {
            var body = json.writeValueAsString(java.util.Map.of("content", "请设置目标职业", "options", java.util.Map.of(
                    "context", java.util.Map.of("careerAction", "setCareerTarget", "occupationId", occupationId,
                            "name", occupationName, "owner", owner(stranger)))));
            var chatRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/agent/chat"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = HttpClient.newHttpClient().send(chatRequest, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                com.fasterxml.jackson.databind.JsonNode approval = null;
                for (String line; (line = reader.readLine()) != null;) {
                    if (!line.startsWith("data:")) continue;
                    var event = json.readTree(line.substring(5).trim());
                    if (event.path("type").asText().equals("approval.required")) { approval = event; break; }
                }
                assertThat(approval).isNotNull();
                assertThat(approval.path("name").asText()).isEqualTo("setCareerTarget");
                assertThat(approval.path("args").path("name").asText()).isEqualTo(occupationName);
                assertThat(approval.path("editableFields")).isEmpty();
                var before = json.readTree(request("GET", "/career/profile", token, null).body()).path("data");
                assertThat(before.path("target").asText()).isEqualTo("原目标");
                String path = "/agent/runs/" + approval.path("runId").asText() + "/approvals/" + approval.path("approvalId").asText();
                assertThat(request("POST", path, stranger, "{\"decision\":\"APPROVE\"}").statusCode()).isEqualTo(404);
                assertThat(request("POST", path, token, "{\"decision\":\"APPROVE\",\"args\":{\"name\":\"其他职业\"}}").statusCode()).isEqualTo(400);
                if (decision.equals("APPROVE")) {
                    // Edits made while approval is pending must survive target adoption.
                    assertThat(request("PUT", "/career/profile", token, json.writeValueAsString(java.util.Map.of(
                            "target", "原目标", "city", "杭州", "skills", "Java、SQL",
                            "interests", java.util.List.of("研究探索")))).statusCode()).isEqualTo(200);
                    before = json.readTree(request("GET", "/career/profile", token, null).body()).path("data");
                }
                String approvalBody = json.writeValueAsString(java.util.Map.of("decision", decision));
                assertThat(request("POST", path, token, approvalBody).statusCode()).isEqualTo(200);
                assertThat(request("POST", path, token, approvalBody).statusCode()).isEqualTo(409);
                var rest = reader.lines().filter(line -> line.startsWith("data:")).map(line -> {
                    try { return json.readTree(line.substring(5).trim()); }
                    catch (IOException ex) { throw new IllegalStateException(ex); }
                }).toList();
                assertThat(rest).anySatisfy(event -> {
                    assertThat(event.path("type").asText()).isEqualTo("approval.resolved");
                    assertThat(event.path("decision").asText()).isEqualTo(decision);
                });
                var result = rest.stream().filter(event -> event.path("type").asText().equals("tool.result")).findFirst().orElseThrow();
                assertThat(result.path("ok").asBoolean()).isEqualTo(decision.equals("APPROVE"));
                var after = json.readTree(request("GET", "/career/profile", token, null).body()).path("data");
                assertThat(after.path("target").asText()).isEqualTo(decision.equals("APPROVE") ? occupationName : "原目标");
                for (String key : java.util.List.of("city", "skills", "interests", "completedTasks"))
                    assertThat(after.path(key)).isEqualTo(before.path(key));
                assertThat(json.readTree(request("GET", "/career/profile", stranger, null).body()).path("data").path("target").asText()).isEmpty();
            }
        }
    }

    @Test
    void coachChatGuidesWithoutTargetThenPersistsBothDeterministicCardsAndReadsOnlyOwnedSkills() throws Exception {
        String token = identities.create().accessToken(), user = owner(token);
        var noTarget = request("POST", "/agent/chat", token, "{\"content\":\"帮我制定学习计划\"}");
        assertThat(noTarget.body()).contains("设为目标").doesNotContain("event:ui.card", "占位", "placeholder.COACH");
        jdbc.update("""
                INSERT INTO occupations(id,name,family,description,core_skills,source) VALUES
                ('occ_coach_fixture','教练测试工程师','技术','fixture',?::jsonb,'fixture')
                ON CONFLICT(id) DO NOTHING
                """, "[{\"name\":\"Java\",\"level\":4},{\"name\":\"SQL\",\"level\":4}]");
        var profile = java.util.Map.of("target", "教练测试工程师", "city", "上海", "skills", "Java", "interests", java.util.List.of("技术研发"));
        assertThat(request("PUT", "/career/profile", token, json.writeValueAsString(profile)).statusCode()).isEqualTo(200);
        String resumeId = "rs_coach_" + user, versionId = "rv_coach_" + user, fileId = "file_coach_" + user;
        var structure = json.readTree(com.offerpilot.resume.application.ResumeStructure.SCHEMA);
        ((com.fasterxml.jackson.databind.node.ObjectNode) structure.path("basics")).put("phone", "private-phone-fixture");
        ((com.fasterxml.jackson.databind.node.ArrayNode) structure.path("skills")).addObject().put("category", "技术").putArray("items").add("SQL");
        com.offerpilot.resume.application.ResumeStructure.validate(structure);
        jdbc.update("""
                INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256)
                VALUES (?,?,'RESUME','fixture',?,'coach.txt','text/plain',1,'fixture')
                """, fileId, user, fileId);
        jdbc.update("INSERT INTO resumes(id,user_id,title,source_file_id,parse_status) VALUES (?,?,'Coach fixture',?,'READY')", resumeId, user, fileId);
        jdbc.update("""
                INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text)
                VALUES (?,?,?,1,'UPLOAD',?::jsonb,'SQL')
                """, versionId, resumeId, user, structure.toString());
        jdbc.update("UPDATE resumes SET current_version_id=? WHERE id=?", versionId, resumeId);
        // No explicit selection: reads the user's latest saved READY current version.
        var response = request("POST", "/agent/chat", token, "{\"content\":\"我该补什么\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        var events = sseEvents(response.body());
        var cards = events.stream().filter(e -> e.path("type").asText().equals("ui.card")).toList();
        assertThat(cards).extracting(e -> e.path("cardType").asText()).containsExactly("skill_gap", "roadmap");
        assertThat(cards.get(0).path("payload").path("skills").get(0).path("name").asText()).isEqualTo("Java");
        assertThat(cards.get(0).path("payload").path("skills").get(1).path("current").asInt()).isEqualTo(3);
        assertThat(cards.get(0).path("payload").path("citations")).isEmpty();
        assertThat(response.body()).doesNotContain("private-phone-fixture", "占位", "approval.required", "placeholder.COACH");
        var persisted = json.readTree(jdbc.queryForObject("SELECT parts::text FROM messages WHERE conversation_id=? AND role='ASSISTANT'",
                String.class, events.get(0).path("conversationId").asText()));
        assertThat(persisted).hasSize(3);
        assertThat(persisted.get(1).path("payload")).isEqualTo(cards.get(0).path("payload"));
        assertThat(persisted.get(2).path("payload")).isEqualTo(cards.get(1).path("payload"));
        var restored = json.readTree(request("GET", "/conversations/" + events.get(0).path("conversationId").asText() + "/messages", token, null).body());
        assertThat(restored.path("data").path("items").get(1).path("parts")).isEqualTo(persisted);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_task_completions WHERE user_id=?", Integer.class, user)).isZero();
        String stranger = identities.create().accessToken();
        assertThat(request("PUT", "/career/profile", stranger, json.writeValueAsString(profile)).statusCode()).isEqualTo(200);
        var foreign = request("POST", "/agent/chat", stranger, json.writeValueAsString(java.util.Map.of("content", "技能差距", "options",
                java.util.Map.of("context", java.util.Map.of("resumeId", resumeId, "resumeVersionId", versionId)))));
        var foreignGap = sseEvents(foreign.body()).stream().filter(e -> e.path("cardType").asText().equals("skill_gap")).findFirst().orElseThrow();
        assertThat(foreignGap.path("payload").path("summary").asText()).contains("仅参考档案技能");
        assertThat(foreignGap.path("payload").path("skills").get(0).path("name").asText()).isEqualTo("SQL");
        assertThat(foreignGap.path("payload").path("skills").get(0).path("current").asInt()).isZero();

        // Close the loop using saved completed artifacts, never a model-selected report or foreign owner.
        String jobId = "job_coach_" + user, matchId = "match_coach_" + user, interviewId = "iv_coach_" + user;
        var job = json.createObjectNode().put("id", jobId).put("title", "教练测试工程师").put("company", "Fixture")
                .put("description", "练习岗位").put("requirement", "Docker SQL");
        jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload) VALUES (?,'fixture',?,'campus',?::jsonb)", jobId, jobId, job.toString());
        var match = json.createObjectNode().put("id", matchId).put("jobId", jobId);
        match.set("jobSnapshot", job);
        match.putArray("requirements").addObject().put("requirement", "Docker").put("status", "MISSING")
                .put("reason", "保存简历未体现容器项目");
        ((com.fasterxml.jackson.databind.node.ArrayNode) match.path("requirements")).addObject()
                .put("requirement", "SQL").put("status", "PARTIAL").put("reason", "缺少项目结果");
        jdbc.update("""
                INSERT INTO job_matches(id,user_id,job_id,resume_version_id,request_id,status,report)
                VALUES (?,?,?,?,?,'COMPLETED',?::jsonb)
                """, matchId, user, jobId, versionId, matchId, match.toString());
        // A newer foreign report even using the same public job must never become coach evidence.
        String foreignMatchId = "match_other_" + user;
        var otherMatch = match.deepCopy().put("id", foreignMatchId);
        otherMatch.putArray("requirements").addObject().put("requirement", "foreign-secret-gap")
                .put("status", "MISSING").put("reason", "foreign-private-evidence");
        jdbc.update("""
                INSERT INTO job_matches(id,user_id,job_id,resume_version_id,request_id,status,report,created_at)
                VALUES (?,?,?,?,?,'COMPLETED',?::jsonb,now()+interval '1 minute')
                """, foreignMatchId, owner(stranger), jobId, versionId, foreignMatchId, otherMatch.toString());
        jdbc.update("""
                INSERT INTO job_matches(id,user_id,job_id,resume_version_id,request_id,status,created_at)
                VALUES (?,?,?,?,?,'RUNNING',now()+interval '2 minutes')
                """, "running_" + user, user, jobId, versionId, "running_" + user);
        jdbc.update("INSERT INTO interview_sessions(id,user_id,role,kind,completed,job_id) VALUES (?,?,'另一练习岗位','项目与经历',true,?)", interviewId, user, jobId);
        jdbc.update("INSERT INTO interview_answers(session_id,question_index,question_text,answer_text) VALUES (?,0,'介绍项目','我做过练习')", interviewId);
        jdbc.update("INSERT INTO interview_sessions(id,user_id,role,kind,completed,updated_at) VALUES (?,?,'foreign-interview','项目与经历',true,now()+interval '1 minute')",
                "iv_foreign_" + user, owner(stranger));
        String applicationId = "app_coach_" + user;
        jdbc.update("INSERT INTO applications(id,user_id,job_id) VALUES (?,?,?)", applicationId, user, jobId);
        var loopResponse = request("POST", "/agent/chat", token, json.writeValueAsString(java.util.Map.of(
                "content", "技能差距和成长计划", "mode", "COACH", "options", java.util.Map.of("context",
                java.util.Map.of("applicationId", applicationId, "jobId", "unrelated-caller-job")))));
        var loopCards = sseEvents(loopResponse.body()).stream().filter(e -> e.path("type").asText().equals("ui.card")).toList();
        assertThat(loopCards).extracting(e -> e.path("cardType").asText()).containsExactly("skill_gap", "roadmap");
        var loopSkills = loopCards.get(0).path("payload").path("skills");
        assertThat(loopSkills).hasSizeLessThanOrEqualTo(8);
        assertThat(loopSkills.get(0).path("name").asText()).isEqualTo("Docker");
        var sources = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var gapIds = new java.util.HashSet<String>();
        for (var skill : loopSkills) {
            skill.path("evidenceSources").forEach(sources::add);
            gapIds.add(skill.path("id").asText());
        }
        assertThat(sources).anySatisfy(source -> {
            assertThat(source.path("type").asText()).isEqualTo("JOB_MATCH");
            assertThat(source.path("id").asText()).isEqualTo(matchId);
            assertThat(source.path("excerpt").asText()).contains("MISSING");
        }).anySatisfy(source -> {
            assertThat(source.path("type").asText()).isEqualTo("INTERVIEW");
            assertThat(source.path("id").asText()).isEqualTo(interviewId);
            assertThat(source.path("label").asText()).contains("另一练习岗位");
        });
        assertThat(sources.stream().filter(source -> source.path("type").asText().equals("INTERVIEW")))
                .allSatisfy(source -> assertThat(source.path("jobId").asText()).isEqualTo(jobId));
        assertThat(loopSkills.toString()).contains("岗位评估：", "面试表现：").doesNotContain("岗位 A ·", "面试 岗位匹配", "面试 逻辑");
        assertThat(loopSkills.toString()).contains("STAR", "PARTIAL").doesNotContain("foreign-secret-gap", "foreign-private-evidence", foreignMatchId, "iv_foreign_");
        for (var week : loopCards.get(1).path("payload").path("weeks"))
            for (var gapId : week.path("gapIds")) assertThat(gapIds).contains(gapId.asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_task_completions WHERE user_id=?", Integer.class, user)).isZero();
        // No context still resolves the exact target title; supplying someone else's selection does not expose their report.
        var byTarget = request("POST", "/agent/chat", token, "{\"content\":\"学习计划\",\"mode\":\"COACH\"}");
        assertThat(byTarget.body()).contains(matchId).doesNotContain(foreignMatchId, "foreign-private-evidence");
        var strangerResponse = request("POST", "/agent/chat", stranger, json.writeValueAsString(java.util.Map.of(
                "content", "成长计划", "mode", "COACH", "options", java.util.Map.of("context", java.util.Map.of("jobId", jobId)))));
        var strangerCard = sseEvents(strangerResponse.body()).stream().filter(e -> e.path("cardType").asText().equals("skill_gap")).findFirst().orElseThrow();
        assertThat(strangerCard.toString()).contains(foreignMatchId).doesNotContain(matchId, interviewId, "保存简历未体现容器项目");

    }

    private java.util.List<com.fasterxml.jackson.databind.JsonNode> sseEvents(String body) {
        return body.lines().filter(line -> line.startsWith("data:")).map(line -> {
            try { return json.readTree(line.substring(5).trim()); }
            catch (IOException ex) { throw new IllegalStateException(ex); }
        }).toList();
    }

    @Test
    void coachGrowthApprovalMergesCompletionsOnlyAfterApprovalAndRejectDoesNotWrite() throws Exception {
        String token = identities.create().accessToken(), stranger = identities.create().accessToken(), user = owner(token);
        var titles = com.offerpilot.career.application.GrowthTaskCatalog.TITLES;
        var initial = java.util.Map.of("target", "原目标", "city", "上海", "skills", "Java", "interests", java.util.List.of("技术研发"));
        assertThat(request("PUT", "/career/profile", token, json.writeValueAsString(initial)).statusCode()).isEqualTo(200);
        assertThat(request("PUT", "/career/tasks", token, json.writeValueAsString(java.util.Map.of("completed", java.util.List.of(titles.get(0))))).statusCode()).isEqualTo(200);
        var originalTime = jdbc.queryForObject("SELECT completed_at::text FROM growth_task_completions WHERE user_id=? AND task_title=?", String.class, user, titles.get(0));
        for (String decision : java.util.List.of("REJECT", "APPROVE")) {
            var body = json.writeValueAsString(java.util.Map.of("content", "同步成长任务", "options", java.util.Map.of("context",
                    java.util.Map.of("coachAction", "adoptGrowthPlan", "taskTitles", java.util.List.of(titles.get(1)), "owner", owner(stranger)))));
            var chatRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/agent/chat"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = HttpClient.newHttpClient().send(chatRequest, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                com.fasterxml.jackson.databind.JsonNode approval = null;
                for (String line; (line = reader.readLine()) != null;) {
                    if (!line.startsWith("data:")) continue;
                    var event = json.readTree(line.substring(5).trim());
                    if (event.path("type").asText().equals("approval.required")) { approval = event; break; }
                }
                assertThat(approval).isNotNull();
                assertThat(approval.path("name").asText()).isEqualTo("adoptGrowthPlan");
                assertThat(approval.path("editableFields")).isEmpty();
                assertThat(approval.path("args").path("taskTitles")).isEqualTo(json.valueToTree(java.util.List.of(titles.get(1))));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_task_completions WHERE user_id=?", Integer.class, user)).isEqualTo(1);
                String path = "/agent/runs/" + approval.path("runId").asText() + "/approvals/" + approval.path("approvalId").asText();
                assertThat(request("POST", path, stranger, "{\"decision\":\"APPROVE\"}").statusCode()).isEqualTo(404);
                assertThat(request("POST", path, token, json.writeValueAsString(java.util.Map.of("decision", "APPROVE", "args", java.util.Map.of("taskTitles", java.util.List.of(titles.get(2)))))).statusCode()).isEqualTo(400);
                if (decision.equals("APPROVE")) {
                    // A checklist change made while waiting must be preserved by the eventual merge.
                    assertThat(request("PUT", "/career/tasks", token, json.writeValueAsString(java.util.Map.of("completed", java.util.List.of(titles.get(0), titles.get(2))))).statusCode()).isEqualTo(200);
                }
                assertThat(request("POST", path, token, json.writeValueAsString(java.util.Map.of("decision", decision))).statusCode()).isEqualTo(200);
                var rest = sseEvents(reader.lines().collect(java.util.stream.Collectors.joining("\n")));
                assertThat(rest).anySatisfy(event -> {
                    assertThat(event.path("type").asText()).isEqualTo("approval.resolved");
                    assertThat(event.path("decision").asText()).isEqualTo(decision);
                });
                var result = rest.stream().filter(e -> e.path("type").asText().equals("tool.result")).findFirst().orElseThrow();
                assertThat(result.path("ok").asBoolean()).isEqualTo(decision.equals("APPROVE"));
                var completed = jdbc.queryForList("SELECT task_title FROM growth_task_completions WHERE user_id=?", String.class, user);
                assertThat(completed).containsExactlyInAnyOrderElementsOf(decision.equals("APPROVE")
                        ? java.util.List.of(titles.get(0), titles.get(1), titles.get(2)) : java.util.List.of(titles.get(0)));
                assertThat(jdbc.queryForObject("SELECT completed_at::text FROM growth_task_completions WHERE user_id=? AND task_title=?", String.class, user, titles.get(0))).isEqualTo(originalTime);
                var profile = json.readTree(request("GET", "/career/profile", token, null).body()).path("data");
                for (String key : initial.keySet()) assertThat(profile.path(key)).isEqualTo(json.valueToTree(initial.get(key)));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_task_completions WHERE user_id=?", Integer.class, owner(stranger))).isZero();
                assertThat(request("POST", path, token, "{\"decision\":\"APPROVE\"}").statusCode()).isEqualTo(409);
            }
        }
    }

    @Test
    void resumePatchChatApprovalCreatesVersionOnlyAfterApprovalAndRejectDoesNotWrite() throws Exception {
        String token = identities.create().accessToken(), stranger = identities.create().accessToken(), user = owner(token);
        String resumeId = "rs_patch_" + user, versionId = "rv_patch_" + user, fileId = "file_patch_" + user;
        var source = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(com.offerpilot.resume.application.ResumeStructure.SCHEMA);
        source.put("summary", "参与后端开发");
        ((com.fasterxml.jackson.databind.node.ArrayNode) source.at("/skills/0/items")).add("SQL");
        jdbc.update("""
                INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256)
                VALUES (?,?,'RESUME','fixture',?,'patch.txt','text/plain',1,'fixture')
                """, fileId, user, fileId);
        jdbc.update("INSERT INTO resumes(id,user_id,title,source_file_id,parse_status) VALUES (?,?,'Patch fixture',?,'READY')", resumeId, user, fileId);
        jdbc.update("""
                INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text)
                VALUES (?,?,?,1,'UPLOAD',?::jsonb,'参与后端开发 SQL')
                """, versionId, resumeId, user, source.toString());
        jdbc.update("UPDATE resumes SET current_version_id=? WHERE id=?", versionId, resumeId);
        var suggestions = json.createArrayNode();
        suggestions.addObject().put("id", "p1").put("op", "replace").put("path", "/summary").put("from", "参与后端开发")
                .put("value", "参与后端接口开发").put("reason", "明确职责").put("needsUserConfirmation", false);
        suggestions.addObject().put("id", "p2").put("op", "replace").put("path", "/skills/0/items/0").put("from", "SQL")
                .put("value", "SQL 与接口测试").put("reason", "核对技能").put("needsUserConfirmation", true).put("confirmationHint", "请核对技能范围");
        var batch = patchService.store(user, resumeId, versionId, suggestions);
        for (String decision : java.util.List.of("REJECT", "APPROVE")) {
            var context = java.util.Map.of("resumeAction", "applyPatches", "resumeId", resumeId, "resumeVersionId", versionId,
                    "batchId", batch.batchId(), "accepted", java.util.List.of("p1"), "edited", java.util.Map.of("p2", java.util.Map.of("value", "SQL 查询")),
                    "confirmed", java.util.List.of("p2"), "owner", owner(stranger));
            var body = json.writeValueAsString(java.util.Map.of("content", "请采纳所选的简历改写建议", "mode", "RESUME",
                    "options", java.util.Map.of("context", context)));
            var chatRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/agent/chat"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = HttpClient.newHttpClient().send(chatRequest, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                com.fasterxml.jackson.databind.JsonNode approval = null;
                boolean writeCalled = false;
                for (String line; (line = reader.readLine()) != null;) {
                    if (!line.startsWith("data:")) continue;
                    var event = json.readTree(line.substring(5).trim());
                    if (event.path("type").asText().equals("tool.call")) {
                        assertThat(event.path("name").asText()).isEqualTo("applyPatches");
                        assertThat(event.path("access").asText()).isEqualTo("WRITE"); writeCalled = true;
                    }
                    if (event.path("type").asText().equals("approval.required")) { approval = event; break; }
                }
                assertThat(writeCalled).isTrue();
                assertThat(approval).isNotNull();
                assertThat(approval.path("editableFields")).isEmpty();
                assertThat(approval.path("args").path("owner").isMissingNode()).isTrue();
                assertThat(patchService.get(user, resumeId, versionId, batch.batchId()).appliedVersionId()).isNull();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM resume_versions WHERE resume_id=?", Integer.class, resumeId)).isEqualTo(1);
                String path = "/agent/runs/" + approval.path("runId").asText() + "/approvals/" + approval.path("approvalId").asText();
                assertThat(request("POST", path, stranger, "{\"decision\":\"APPROVE\"}").statusCode()).isEqualTo(404);
                assertThat(request("POST", path, token, "{\"decision\":\"APPROVE\",\"args\":{\"accepted\":[]}}").statusCode()).isEqualTo(400);
                assertThat(request("POST", path, token, json.writeValueAsString(java.util.Map.of("decision", decision))).statusCode()).isEqualTo(200);
                var rest = sseEvents(reader.lines().collect(java.util.stream.Collectors.joining("\n")));
                assertThat(rest).anySatisfy(event -> {
                    assertThat(event.path("type").asText()).isEqualTo("approval.resolved");
                    assertThat(event.path("decision").asText()).isEqualTo(decision);
                });
                var result = rest.stream().filter(e -> e.path("type").asText().equals("tool.result")).findFirst().orElseThrow();
                assertThat(result.path("ok").asBoolean()).isEqualTo(decision.equals("APPROVE"));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM resume_versions WHERE resume_id=?", Integer.class, resumeId))
                        .isEqualTo(decision.equals("APPROVE") ? 2 : 1);
                if (decision.equals("APPROVE")) {
                    var savedBatch = patchService.get(user, resumeId, versionId, batch.batchId());
                    assertThat(savedBatch.appliedVersionId()).isNotNull();
                    var version = json.readTree(request("GET", "/resumes/" + resumeId + "/versions/" + savedBatch.appliedVersionId(), token, null).body()).path("data");
                    assertThat(version.path("structured").path("summary").asText()).isEqualTo("参与后端接口开发");
                    assertThat(version.path("structured").at("/skills/0/items/0").asText()).isEqualTo("SQL 查询");
                    assertThat(version.path("versionNo").asInt()).isEqualTo(2);
                    assertThat(version.path("parentVersionId").asText()).isEqualTo(versionId);
                    assertThat(result.path("resultPreview").asText()).contains("V2", "原版本保留").doesNotContain(savedBatch.appliedVersionId());
                }
                assertThat(request("POST", path, token, "{\"decision\":\"APPROVE\"}").statusCode()).isEqualTo(409);
            }
        }
        assertThat(json.readTree(jdbc.queryForObject("SELECT structured::text FROM resume_versions WHERE id=?", String.class, versionId)))
                .isEqualTo(source);
    }

    private void boardJobFixture() {
        jdbc.update("""
                INSERT INTO jobs(id,site,external_id,nature,payload) VALUES ('job_board_fixture','fixture','board','campus',?::jsonb)
                ON CONFLICT (id) DO NOTHING
                """, json.createObjectNode().put("company", "BoardCorp").put("title", "Java 岗位")
                .put("site", "fixture").put("nature", "campus").toString());
    }

    @Test
    void boardChatApprovalCreatesUpdatesAndRejectsThroughRealHttpWithOwnerIsolation() throws Exception {
        boardJobFixture();
        String token = identities.create().accessToken(), user = owner(token);
        String stranger = identities.create().accessToken();
        String applicationId = null;
        for (String operation : java.util.List.of("新建", "更新", "拒绝")) {
            String identifier = applicationId;
            var context = operation.equals("新建") ? java.util.Map.of("jobId", "job_board_fixture")
                    : java.util.Map.of("applicationId", identifier);
            var body = json.writeValueAsString(java.util.Map.of("content", "看板审批验收：" + operation,
                    "options", java.util.Map.of("context", context)));
            var chatRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/agent/chat"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = HttpClient.newHttpClient().send(chatRequest, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                var events = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
                com.fasterxml.jackson.databind.JsonNode approval = null;
                for (String line; (line = reader.readLine()) != null;) {
                    if (!line.startsWith("data:")) continue;
                    var event = json.readTree(line.substring(5).trim());
                    events.add(event);
                    if (event.path("type").asText().equals("approval.required")) { approval = event; break; }
                }
                assertThat(approval).isNotNull();
                assertThat(events.get(events.size()-2).path("access").asText()).isEqualTo("WRITE");
                assertThat(events.get(events.size()-2).path("type").asText()).isEqualTo("tool.call");
                assertThat(approval.path("editableFields").toString()).contains("status", "note");
                if (operation.equals("新建")) assertThat(applications.list(user)).isEmpty();
                else assertThat(applications.list(user).get(0).status()).isEqualTo(operation.equals("更新") ? "APPLIED" : "INTERVIEW");
                String path = "/agent/runs/" + approval.path("runId").asText() + "/approvals/" + approval.path("approvalId").asText();
                assertThat(request("POST", path, stranger, "{\"decision\":\"APPROVE\"}").statusCode()).isEqualTo(404);
                assertThat(request("POST", path + "missing", token, "{\"decision\":\"APPROVE\"}").statusCode()).isEqualTo(404);
                assertThat(request("POST", path, token, "{\"decision\":\"MAYBE\"}").statusCode()).isEqualTo(400);
                assertThat(request("POST", path, token, "{\"decision\":\"APPROVE\",\"args\":{\"status\":\"WRITTEN_TEST\"}}").statusCode()).isEqualTo(400);
                assertThat(request("POST", path, token, "{\"decision\":\"APPROVE\",\"args\":{\"applicationId\":\"other\"}}").statusCode()).isEqualTo(400);
                boolean reject = operation.equals("拒绝");
                var decision = reject ? "{\"decision\":\"REJECT\"}" : json.writeValueAsString(java.util.Map.of(
                        "decision", "APPROVE", "args", java.util.Map.of("status", operation.equals("新建") ? "APPLIED" : "INTERVIEW", "note", "审批备注", "nextAction", "准备项目复盘", "nextActionAt", "2026-10-12")));
                assertThat(request("POST", path, token, decision).statusCode()).isEqualTo(200);
                assertThat(request("POST", path, token, decision).statusCode()).isEqualTo(409);
                for (String line; (line = reader.readLine()) != null;)
                    if (line.startsWith("data:")) events.add(json.readTree(line.substring(5).trim()));
                assertThat(events).anySatisfy(e -> {
                    assertThat(e.path("type").asText()).isEqualTo("approval.resolved");
                    assertThat(e.path("decision").asText()).isEqualTo(reject ? "REJECT" : "APPROVE");
                });
                var result = events.stream().filter(e -> e.path("type").asText().equals("tool.result")).findFirst().orElseThrow();
                assertThat(result.path("ok").asBoolean()).isEqualTo(!reject);
                var cards = events.stream().filter(e -> e.path("type").asText().equals("ui.card")).toList();
                if (reject) {
                    assertThat(cards).isEmpty();
                    assertThat(result.path("resultPreview").asText()).contains("你已拒绝", "看板修改未执行", "数据未改动", "重新发起");
                } else {
                    assertThat(cards).hasSize(1);
                    assertThat(cards.get(0).path("cardType").asText()).isEqualTo("board_update");
                    assertThat(cards.get(0).path("payload").path("change").asText()).isEqualTo(operation.equals("新建") ? "CREATED" : "UPDATED");
                    assertThat(cards.get(0).path("payload").path("application").path("company").asText()).isEqualTo("BoardCorp");
                    assertThat(cards.get(0).path("payload").path("application").path("nextActionAt").asText()).isEqualTo("2026-10-12");
                }
                var saved = applications.list(user);
                assertThat(saved).hasSize(1);
                applicationId = saved.get(0).id();
                assertThat(saved.get(0).status()).isEqualTo(operation.equals("新建") ? "APPLIED" : "INTERVIEW");
                assertThat(saved.get(0).note()).isEqualTo("审批备注");
                assertThat(saved.get(0).nextAction()).isEqualTo("准备项目复盘");
                assertThat(events.stream().map(e -> e.path("id").asInt()).toList()).containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, events.size()).boxed().toList());
                String conv = events.get(0).path("conversationId").asText();
                if (!reject) assertThat(conversations.messages(user, conv, null, 30).items().get(1).parts().get(1).path("cardType").asText()).isEqualTo("board_update");
            }
        }
        assertThat(applications.list(owner(stranger))).isEmpty();
        String finalId = applicationId;
        var bad = request("POST", "/agent/chat", token, json.writeValueAsString(java.util.Map.of("content", "看板审批验收：非法状态",
                "options", java.util.Map.of("context", java.util.Map.of("applicationId", finalId)))));
        assertThat(bad.body()).contains("状态仅支持", "\"ok\":false").doesNotContain("event:approval.required", "event:ui.card");
        assertThat(applications.list(user).get(0).status()).isEqualTo("INTERVIEW");
        var missing = request("POST", "/agent/chat", token, "{\"content\":\"看板审批验收：更新\"}");
        assertThat(missing.body()).doesNotContain("event:approval.required", "event:ui.card");
    }

    @Test
    void terminalMessagesPersistAcceptedCardsAndKeepTextWhenCardsAreRejected() throws Exception {
        String token = identities.create().accessToken();
        String user = owner(token);
        String conv = conversations.create(user, null, null).id();
        var match = json.createObjectNode().put("score", 80);
        var turn = conversations.begin(user, conv, "匹配这个岗位", null, "test");
        conversations.finish(turn, "已生成匹配报告", "COMPLETED", null, null, java.util.List.of(
                new MessageCard("card_job_1", "job_match", match),
                new MessageCard("card_job_1", "job_match", json.createObjectNode().put("score", 1)),
                new MessageCard("bad id", "job_match", match),
                new MessageCard("card_nope", "not_a_card", match),
                new MessageCard("card_board_1", "board_update", json.createObjectNode().put("change", "CREATED"))));
        var body = json.readTree(request("GET", "/conversations/" + conv + "/messages", token, null).body());
        var assistant = body.path("data").path("items").get(1);
        assertThat(assistant.path("plainText").asText()).isEqualTo("已生成匹配报告");
        assertThat(assistant.path("parts")).hasSize(3);
        assertThat(assistant.path("parts").get(0).path("type").asText()).isEqualTo("text");
        assertThat(assistant.path("parts").get(1).path("cardId").asText()).isEqualTo("card_job_1");
        assertThat(assistant.path("parts").get(1).path("cardType").asText()).isEqualTo("job_match");
        assertThat(assistant.path("parts").get(1).path("payload").path("score").asInt()).isEqualTo(80);
        assertThat(assistant.path("parts").get(2).path("cardId").asText()).isEqualTo("card_board_1");

        var plain = conversations.begin(user, conv, "只要文字", null, "test");
        conversations.finish(plain, "只有文字", "COMPLETED", null, null);
        var textOnly = conversations.messages(user, conv, null, 10).items().stream()
                .filter(message -> message.plainText().equals("只有文字")).findFirst().orElseThrow();
        assertThat(textOnly.parts()).hasSize(1);
        assertThat(textOnly.parts().get(0).path("type").asText()).isEqualTo("text");

        var failed = conversations.begin(user, conv, "失败的问题", null, "test");
        conversations.finish(failed, "失败前的文字", "FAILED", null, "test failure",
                java.util.List.of(new MessageCard("card_gap_1", "skill_gap", json.createObjectNode().put("overall", 1))));
        var failedMessage = conversations.messages(user, conv, null, 20).items().stream()
                .filter(message -> message.plainText().equals("失败前的文字")).findFirst().orElseThrow();
        assertThat(failedMessage.status()).isEqualTo("FAILED");
        assertThat(failedMessage.parts().get(1).path("cardId").asText()).isEqualTo("card_gap_1");

        var cancelled = conversations.begin(user, conv, "取消的问题", null, "test");
        conversations.cancel(user, cancelled.runId(), "取消前的文字",
                java.util.List.of(new MessageCard("card_resume_1", "resume_diff", json.createObjectNode().put("batchId", "pb_1"))));
        var cancelledMessage = conversations.messages(user, conv, null, 20).items().stream()
                .filter(message -> "取消前的文字".equals(message.plainText())).findFirst().orElseThrow();
        assertThat(cancelledMessage.status()).isEqualTo("CANCELLED");
        assertThat(cancelledMessage.parts().get(1).path("cardType").asText()).isEqualTo("resume_diff");
        assertThat(cancelledMessage.parts().get(1).path("cardId").asText()).isEqualTo("card_resume_1");
    }
}
