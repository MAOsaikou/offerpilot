package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.resume.application.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.ai.model.chat=deepseek", "spring.ai.model.embedding=none", "spring.ai.retry.max-attempts=1",
    "spring.ai.deepseek.api-key=test-only-key", "spring.ai.openai.api-key=test-only-vision-key",
    "offerpilot.llm.default-tier=CHEAP", "spring.ai.mcp.server.enabled=false", "offerpilot.resume.worker-enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class ResumeHttpIntegrationTest {
    @Container static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container static GenericContainer<?> minio = new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:latest"))
        .withEnv("MINIO_ROOT_USER", "resume-test").withEnv("MINIO_ROOT_PASSWORD", "resume-test-password")
        .withCommand("server", "/data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicReference<String> output = new AtomicReference<>(ResumeStructure.SCHEMA);
    static final AtomicReference<String> lastPrompt = new AtomicReference<>();
    static final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    static final AtomicReference<String> lastPath = new AtomicReference<>();
    static final java.util.concurrent.atomic.AtomicInteger providerStatus = new java.util.concurrent.atomic.AtomicInteger(200);
    static final HttpServer provider = provider();
    @Autowired ResumeService resumes;
    @Autowired ResumeParseWorker worker;
    @Autowired ResumeObjectStore store;
    @Autowired GuestIdentityService identities;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.ai.deepseek.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
        registry.add("offerpilot.storage.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
        registry.add("offerpilot.storage.access-key", () -> "resume-test");
        registry.add("offerpilot.storage.secret-key", () -> "resume-test-password");
        registry.add("offerpilot.storage.bucket", () -> "resume-test");
    }
    static HttpServer provider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                lastPrompt.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                lastPath.set(exchange.getRequestURI().getPath());
                if (providerStatus.get() != 200) {
                    exchange.sendResponseHeaders(providerStatus.get(), 0);
                    try (var stream = exchange.getResponseBody()) { stream.write("private-provider-diagnostic".getBytes(StandardCharsets.UTF_8)); }
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
                var frame = Map.of("id", "resume-test", "object", "chat.completion.chunk", "created", 1,
                    "model", "test-model", "usage", Map.of("prompt_tokens", 12, "completion_tokens", 6, "total_tokens", 18),
                    "choices", List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "content", output.get()))));
                try (var stream = exchange.getResponseBody()) {
                    stream.write(("data: " + JSON.writeValueAsString(frame) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                }
            });
            server.start(); return server;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    @AfterAll static void stop() { provider.stop(0); }
    @BeforeEach void reset() { output.set(ResumeStructure.SCHEMA); providerStatus.set(200); }
    String owner(String token) { return identities.require("Bearer " + token); }
    JsonNode data(HttpResponse<String> response) throws Exception { return JSON.readTree(response.body()).path("data"); }
    HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(30));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.header("Content-Type", "application/json");
        return HttpClient.newHttpClient().send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    HttpResponse<String> upload(String token, String name, byte[] bytes) throws Exception {
        String boundary = "resume-test-boundary";
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/resumes"))
            .timeout(Duration.ofSeconds(30)).header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    @Test void uploadParseEditRestoreAndOwnership() throws Exception {
        String token = identities.create().accessToken(), stranger = identities.create().accessToken();
        var uploaded = upload(token, "resume.txt", "张同学 软件工程 Java".getBytes(StandardCharsets.UTF_8));
        assertThat(uploaded.statusCode()).isEqualTo(200);
        String id = data(uploaded).path("id").asText();
        assertThat(data(uploaded).path("parseStatus").asText()).isEqualTo("QUEUED");
        var file = jdbc.queryForMap("SELECT f.* FROM files f JOIN resumes r ON r.source_file_id=f.id WHERE r.id=?", id);
        assertThat(new String(store.get(file.get("bucket").toString(), file.get("object_key").toString()), StandardCharsets.UTF_8)).contains("张同学");
        worker.processNext();
        var detail = data(request("GET", "/resumes/" + id, token, null));
        assertThat(detail.path("parseStatus").asText()).isEqualTo("READY");
        assertThat(lastPrompt.get()).contains("张同学").contains("不要填入示例经历").doesNotContain("test-only-key");
        String base = detail.path("currentVersionId").asText();
        var structure = (com.fasterxml.jackson.databind.node.ObjectNode) detail.path("currentVersion").path("structured").deepCopy();
        structure.withObject("/basics").put("name", "张同学");
        var saved = request("PUT", "/resumes/" + id + "/versions/" + base + "/structured", token, structure.toString());
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(data(saved).path("currentVersion").path("versionNo").asInt()).isEqualTo(2);
        assertThat(request("PUT", "/resumes/" + id + "/versions/" + base + "/structured", token, structure.toString()).statusCode()).isEqualTo(409);
        assertThat(data(request("GET", "/resumes/" + id + "/versions", token, null)).size()).isEqualTo(2);
        assertThat(data(request("GET", "/resumes/" + id, token, null)).path("currentVersion").path("structured").path("basics").path("name").asText()).isEqualTo("张同学");
        assertThat(data(request("GET", "/resumes", stranger, null)).path("total").asInt()).isZero();
        for (String path : List.of("/resumes/" + id, "/resumes/" + id + "/parse-status", "/resumes/" + id + "/versions", "/resumes/" + id + "/versions/" + base))
            assertThat(request("GET", path, stranger, null).statusCode()).isEqualTo(404);
        assertThat(request("POST", "/resumes/" + id + "/retry", stranger, null).statusCode()).isEqualTo(404);
        assertThat(request("PATCH", "/resumes/" + id, stranger, "{\"title\":\"attack\"}").statusCode()).isEqualTo(404);
        assertThat(request("PUT", "/resumes/" + id + "/versions/" + base + "/structured", stranger, structure.toString()).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/resumes", null, null).statusCode()).isEqualTo(401);
    }
    @Test void failureRetryLeaseRecoveryAndStaleWorkerCannotOverwrite() throws Exception {
        String token = identities.create().accessToken();
        String id = data(upload(token, "retry.md", "实际经历".getBytes(StandardCharsets.UTF_8))).path("id").asText();
        output.set("invalid json"); worker.processNext();
        assertThat(resumes.get(owner(token), id).parseStatus()).isEqualTo("FAILED");
        assertThat(resumes.versions(owner(token), id)).isEmpty();
        assertThat(request("POST", "/resumes/" + id + "/retry", token, null).statusCode()).isEqualTo(200);
        var abandoned = resumes.claim();
        assertThat(resumes.claim()).isNull();
        jdbc.update("UPDATE resumes SET parse_started_at=now()-interval '11 minutes' WHERE id=?", id);
        assertThat(resumes.claim()).isNull();
        assertThat(resumes.get(owner(token), id).parseStatus()).isEqualTo("FAILED");
        resumes.retry(owner(token), id);
        output.set(ResumeStructure.SCHEMA); worker.processNext();
        resumes.fail(abandoned, "stale result");
        resumes.complete(abandoned, JSON.readTree(ResumeStructure.SCHEMA), new ResumeDocumentReader.Document("", List.of(), "text"));
        assertThat(resumes.get(owner(token), id).parseStatus()).isEqualTo("READY");
        assertThat(resumes.versions(owner(token), id)).hasSize(1);
    }
    @Test void rejectsInvalidUploadsAndPersistsCorruptDocxFailure() throws Exception {
        String token = identities.create().accessToken();
        assertThat(upload(null, "resume.txt", "text".getBytes()).statusCode()).isEqualTo(401);
        assertThat(upload(token, "resume.pdf", "not a pdf".getBytes()).statusCode()).isEqualTo(400);
        assertThat(upload(token, "empty.txt", new byte[0]).statusCode()).isEqualTo(400);
        assertThat(upload(token, "large.txt", new byte[10 * 1024 * 1024 + 1]).statusCode()).isEqualTo(413);
        String id = data(upload(token, "broken.docx", new byte[]{80,75,3,4,0})).path("id").asText();
        worker.processNext();
        assertThat(resumes.get(owner(token), id).parseStatus()).isEqualTo("FAILED");
    }
    @Test void imageUsesVisionProviderAndIncludesImageData() throws Exception {
        String token = identities.create().accessToken();
        var image = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        String id = data(upload(token, "resume.png", bytes.toByteArray())).path("id").asText();
        worker.processNext();
        assertThat(resumes.get(owner(token), id).parseStatus()).isEqualTo("READY");
        assertThat(lastPrompt.get()).contains("image_url").contains("data:image/png;base64,");
        assertThat(lastAuthorization.get()).isEqualTo("Bearer test-only-key");
        assertThat(lastPath.get()).isEqualTo("/chat/completions");
        assertThat(JSON.readTree(lastPrompt.get()).path("model").asText()).isEqualTo("deepseek-flash");
        var meta = resumes.get(owner(token), id).currentVersion().parseMeta();
        assertThat(meta.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(meta.path("promptVersion").asText()).isEqualTo("v2");
        assertThat(meta.path("imageCount").asInt()).isEqualTo(1);
        assertThat(meta.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(meta.path("totalTokens").asInt()).isEqualTo(18);
    }
    @Test void providerFailuresAreActionableAndDoNotLeakResponseBody() throws Exception {
        String token = identities.create().accessToken();
        var image = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        for (int status : List.of(401, 429, 503)) {
            providerStatus.set(status);
            String id = data(upload(token, "failure.png", bytes.toByteArray())).path("id").asText();
            worker.processNext();
            var resume = resumes.get(owner(token), id);
            assertThat(resume.parseStatus()).isEqualTo("FAILED");
            assertThat(resume.parseError()).doesNotContain("private-provider-diagnostic", "test-only-key")
                .contains(status == 401 ? "鉴权" : status == 429 ? "受限" : "503");
            assertThat(resumes.versions(owner(token), id)).isEmpty();
        }
    }
    @Test void modelSettingsArePrivateValidatedAndSnapshottedForEachJob() throws Exception {
        String token = identities.create().accessToken(), stranger = identities.create().accessToken();
        assertThat(request("GET", "/resume-model-settings", null, null).statusCode()).isEqualTo(401);
        var catalog = request("GET", "/resume-model-settings", token, null);
        assertThat(catalog.statusCode()).isEqualTo(200);
        assertThat(catalog.body()).doesNotContain("test-only-key", "test-only-vision-key", "apiKey", "baseUrl");
        assertThat(data(catalog).path("selection").path("textTier").asText()).isEqualTo("CHEAP");
        assertThat(request("PUT", "/resume-model-settings", token, "{\"textTier\":\"CHEAP\",\"visionTier\":\"PRO\"}").statusCode()).isEqualTo(400);
        assertThat(request("PUT", "/resume-model-settings", token, "{\"textTier\":\"unknown\",\"visionTier\":\"CHEAP\"}").statusCode()).isEqualTo(400);
        String id = data(upload(token, "before.txt", "测试简历".getBytes(StandardCharsets.UTF_8))).path("id").asText();
        assertThat(request("PUT", "/resume-model-settings", token, "{\"textTier\":\"VISION_ALT\",\"visionTier\":\"VISION_ALT\"}").statusCode()).isEqualTo(200);
        assertThat(data(request("GET", "/resume-model-settings", token, null)).path("selection").path("textTier").asText()).isEqualTo("VISION_ALT");
        assertThat(data(request("GET", "/resume-model-settings", stranger, null)).path("selection").path("textTier").asText()).isEqualTo("CHEAP");
        var queued = resumes.claim();
        assertThat(queued.id()).isEqualTo(id);
        assertThat(queued.textTier()).isEqualTo("CHEAP");
        resumes.fail(queued, "模拟失败");
        resumes.retry(owner(token), id);
        var retried = resumes.claim();
        assertThat(retried.textTier()).isEqualTo("VISION_ALT");
        resumes.fail(retried, "结束夹具");
        String nextId = data(upload(token, "after.txt", "新简历".getBytes(StandardCharsets.UTF_8))).path("id").asText();
        worker.processNext();
        assertThat(resumes.get(owner(token), nextId).currentVersion().parseMeta().path("tier").asText()).isEqualTo("VISION_ALT");
    }
}
