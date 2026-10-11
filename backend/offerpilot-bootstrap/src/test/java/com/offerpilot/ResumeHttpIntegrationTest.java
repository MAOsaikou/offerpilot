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
    "offerpilot.llm.default-tier=FLASH", "spring.ai.mcp.server.enabled=false", "offerpilot.resume.worker-enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class ResumeHttpIntegrationTest {
    @Autowired ResumeDiagnosisService diagnoses;
    @org.springframework.boot.test.mock.mockito.SpyBean ResumeDiagnoser diagnoser;
    @org.springframework.boot.test.mock.mockito.SpyBean ResumeOptimizer optimizer;
    @Container static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    // quay.io/minio/minio 匿名 token 的 actions 为空，manifest 返回 UNAUTHORIZED。
    // 固定 adobe/s3mock:5.2.0 的清单 digest。Testcontainers 1.21 不能同时解析 tag 与 digest。
    @Container static GenericContainer<?> minio = new GenericContainer<>(DockerImageName.parse("adobe/s3mock@sha256:7a37f0d796e81a28b970c892dcae532797014616b3312b467af8f0274ebf0c26"))
        .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", "resume-test").withExposedPorts(9090)
        .waitingFor(Wait.forHttp("/").forPort(9090).forStatusCode(200));
    static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicReference<String> output = new AtomicReference<>(ResumeStructure.SCHEMA);
    static final AtomicReference<String> lastPrompt = new AtomicReference<>();
    static final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    static final AtomicReference<String> lastPath = new AtomicReference<>();
    static final java.util.concurrent.atomic.AtomicInteger providerStatus = new java.util.concurrent.atomic.AtomicInteger(200);
    static final java.util.concurrent.atomic.AtomicInteger providerCalls = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger rewriteCalls = new java.util.concurrent.atomic.AtomicInteger();
    static final HttpServer provider = provider();
    @Autowired ResumeService resumes;
    @Autowired ResumePatchService patches;
    @Autowired ResumeOptimizationService optimizations;
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
        registry.add("offerpilot.storage.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9090));
        registry.add("offerpilot.storage.access-key", () -> "resume-test");
        registry.add("offerpilot.storage.secret-key", () -> "resume-test-password");
        registry.add("offerpilot.storage.bucket", () -> "resume-test");
    }
    static HttpServer provider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                providerCalls.incrementAndGet();
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
                String content = output.get(), prompt = lastPrompt.get();
                if (prompt.contains("简历动作解析 v1")) content = prompt.contains("本轮请求：解释已有建议") ? "{\"action\":\"discuss\"}" : "{\"action\":\"optimize\"}";
                else if (prompt.contains("意图识别与规划 v1")) content = "{\"kind\":\"single\",\"expert\":\"RESUME\",\"confidence\":1}";
                else if (prompt.contains("中文应届生简历的岗位定向编辑")) rewriteCalls.incrementAndGet();
                else if (prompt.contains("本轮分析结果已作为卡片展示")) content = "建议已保存，请核对后采纳。";
                else if (prompt.contains("本轮真实业务调用未成功")) content = "本次调用失败，请检查选定资源。";
                var frame = Map.of("id", "resume-test", "object", "chat.completion.chunk", "created", 1,
                    "model", "test-model", "usage", Map.of("prompt_tokens", 12, "completion_tokens", 6, "total_tokens", 18),
                    "choices", List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "content", content))));
                try (var stream = exchange.getResponseBody()) {
                    stream.write(("data: " + JSON.writeValueAsString(frame) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                }
            });
            server.start(); return server;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    @AfterAll static void stop() { provider.stop(0); }
    @BeforeEach void reset() { output.set(ResumeStructure.SCHEMA); providerStatus.set(200); providerCalls.set(0); rewriteCalls.set(0); }
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
    @Test void diagnosisSsePersistsVersionEvidenceAndRestoresWithoutAnotherModelCall() throws Exception {
        String token=identities.create().accessToken(), stranger=identities.create().accessToken();
        var structure=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(ResumeStructure.SCHEMA);
        structure.put("summary","负责缓存优化"); structure.withObject("/basics").put("email","private@example.test");
        output.set(structure.toString());
        String id=data(upload(token,"diagnosis.txt","虚构简历".getBytes(StandardCharsets.UTF_8))).path("id").asText();
        worker.processNext();
        String version=resumes.get(owner(token),id).currentVersionId();
        output.set("""
            {"summary":"补充个人行动和验证结果", "strengths":[],"findings":[{"ruleId":"CONTRIBUTION","kind":"NEEDS_CLARIFICATION","priority":"MEDIUM","evidence":[{"path":"/summary","quote":"负责缓存优化"}],"reason":"缺少个人行动细节","suggestion":"补充真实策略","questions":["具体采用什么策略？"]}]}
            """);
        String path="/resumes/"+id+"/versions/"+version+"/analyze", body="{\"requestId\":\"diagnosis-request-0001\"}";
        assertThat(request("POST",path,stranger,body).statusCode()).isEqualTo(404);
        var response=request("POST",path,token,body);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("run.started","ui.card","resume_diagnosis","run.completed").doesNotContain("private@example.test");
        assertThat(lastPrompt.get()).doesNotContain("private@example.test");
        var list=data(request("GET","/resumes/"+id+"/diagnoses",token,null));
        String reportId=list.get(0).path("id").asText();
        var report=data(request("GET","/resumes/diagnoses/"+reportId,token,null)).path("report");
        assertThat(report.path("versionId").asText()).isEqualTo(version);
        assertThat(report.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(report.path("inputTokens").asInt()).isEqualTo(12);
        assertThat(report.path("outputTokens").asInt()).isEqualTo(6);
        providerStatus.set(503);
        assertThat(request("POST",path,token,body).body()).contains("ui.card").doesNotContain("event:error");
        assertThat(request("GET","/resumes/diagnoses/"+reportId,stranger,null).statusCode()).isEqualTo(404);
        assertThat(request("POST","/resumes/diagnoses/"+reportId+"/cancel",stranger,null).statusCode()).isEqualTo(404);
        structure.put("summary","新版内容");
        var updated=resumes.save(owner(token),id,version,structure);
        assertThat(diagnoses.report(owner(token),id,version,reportId)).isEqualTo(report);
        assertThatThrownBy(() -> diagnoses.report(owner(token),id,updated.currentVersionId(),reportId)).hasMessageContaining("不存在");
        assertThat(request("POST","/resumes/"+id+"/versions/"+updated.currentVersionId()+"/analyze",token,body).statusCode()).isEqualTo(400);
    }
    private void cancelStream(String token, String path, String body, String listPath, String kind,
                              java.util.concurrent.CountDownLatch started, java.util.concurrent.CountDownLatch stopped) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var inFlight=HttpClient.newHttpClient().sendAsync(req,HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(started.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String taskId=data(request("GET",listPath,token,null)).get(0).path("id").asText();
            assertThat(request("POST","/generation-tasks/"+kind+"/"+taskId+"/cancel",token,null).statusCode()).isEqualTo(200);
            var response=inFlight.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("event:error","30205","已取消").doesNotContain("event:ui.card","过期");
            assertThat(stopped.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(data(request("GET",listPath,token,null)).toString()).contains("CANCELLED");
        } finally { inFlight.cancel(true); }
    }

    @Test void diagnosisCancellationExpirationAndInvalidOutputCannotPublishReport() throws Exception {
        String token=identities.create().accessToken();
        String id=data(upload(token,"cancel.txt","虚构简历".getBytes(StandardCharsets.UTF_8))).path("id").asText(); worker.processNext();
        String version=resumes.get(owner(token),id).currentVersionId();
        var started=new java.util.concurrent.CountDownLatch(1);
        var stopped=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doReturn(reactor.core.publisher.Mono.<com.fasterxml.jackson.databind.node.ObjectNode>never()
                .doOnSubscribe(ignored -> started.countDown()).doOnCancel(stopped::countDown))
                .when(diagnoser).diagnose(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        cancelStream(token,"/resumes/"+id+"/versions/"+version+"/analyze",
                "{\"requestId\":\"diagnosis-request-dock\"}","/resumes/"+id+"/diagnoses","RESUME_DIAGNOSIS",started,stopped);
        org.mockito.Mockito.reset(diagnoser);
        var work=diagnoses.begin(owner(token),id,version,"diagnosis-request-cancel");
        assertThatThrownBy(() -> diagnoses.begin(owner(token),id,version,"diagnosis-request-other")).hasMessageContaining("进行中");
        diagnoses.cancel(owner(token),work.id());
        assertThat(request("POST","/resumes/"+id+"/versions/"+version+"/analyze",token,
                "{\"requestId\":\"diagnosis-request-cancel\"}").statusCode()).isEqualTo(409);
        assertThatThrownBy(() -> diagnoses.execute(work).block(Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(com.offerpilot.common.error.BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(com.offerpilot.common.error.ErrorCode.GENERATION_CANCELLED));
        assertThat(diagnoses.get(owner(token),work.id()).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(diagnoses.get(owner(token),work.id()).has("report")).isFalse();
        var expired=diagnoses.begin(owner(token),id,version,"diagnosis-request-expired");
        jdbc.update("UPDATE resume_diagnoses SET created_at=now()-interval '4 minutes' WHERE id=?",expired.id());
        assertThat(diagnoses.get(owner(token),expired.id()).path("status").asText()).isEqualTo("FAILED");
        assertThatThrownBy(() -> diagnoses.execute(expired).block(Duration.ofSeconds(3))).hasMessageContaining("过期");
        output.set("not a report");
        var failed=request("POST","/resumes/"+id+"/versions/"+version+"/analyze",token,"{\"requestId\":\"diagnosis-request-invalid\"}");
        assertThat(failed.body()).contains("event:error").doesNotContain("event:ui.card");
        assertThat(diagnoses.list(owner(token),id).get(0).path("status").asText()).isEqualTo("FAILED");
    }
    @Test void uploadParseEditRestoreAndOwnership() throws Exception {
        String token = identities.create().accessToken(), stranger = identities.create().accessToken();
        for (int size : new int[] {10 * 1024 * 1024 + 1, 16 * 1024 * 1024}) {
            var oversized=upload(token,"oversized.pdf",new byte[size]);
            assertThat(oversized.statusCode()).isEqualTo(413);
            assertThat(oversized.headers().firstValue("Content-Type").orElse("")).contains("application/json");
            assertThat(oversized.body()).contains("10003","简历文件不能超过 10MB");
        }
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
    ResumeService.Resume patchFixture(String token) throws Exception {
        var structure = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(ResumeStructure.SCHEMA);
        structure.put("summary", "负责后端开发");
        ((com.fasterxml.jackson.databind.node.ArrayNode) structure.get("projects")).removeAll().add(JSON.readTree("""
            {"name":"校园平台","role":"后端","start":"2025","end":"2026","tech":["Java"],"bullets":["实现登录","优化缓存"]}
            """));
        output.set(structure.toString());
        String id = data(upload(token, "patch.txt", "虚构简历".getBytes(StandardCharsets.UTF_8))).path("id").asText();
        worker.processNext();
        return resumes.get(owner(token), id);
    }
    ResumePatchService.Batch batch(String token, ResumeService.Resume resume) throws Exception {
        return patches.store(owner(token), resume.id(), resume.currentVersionId(), JSON.readTree("""
            [{"id":"p1","op":"replace","path":"/summary","from":"负责后端开发","value":"设计与实现后端服务","reason":"突出贡献","needsUserConfirmation":false},
             {"id":"p2","op":"replace","path":"/projects/0/bullets/0","from":"实现登录","value":"实现登录与权限校验","reason":"明确职责","needsUserConfirmation":true,"confirmationHint":"请核对实际贡献"},
             {"id":"p3","op":"replace","path":"/projects/0/bullets/1","from":"优化缓存","value":"优化缓存策略","reason":"明确行动","needsUserConfirmation":false}]
            """));
    }
    String applyPath(ResumeService.Resume resume) {
        return "/resumes/" + resume.id() + "/versions/" + resume.currentVersionId() + "/apply-patches";
    }
    String decision(ResumePatchService.Batch batch, String accepted) {
        return "{\"batchId\":\"" + batch.batchId() + "\",\"accepted\":[\"" + accepted + "\"]}";
    }
    @Test void patchesApplyEditedSubsetPreserveHistoryAndRetryOriginalResult() throws Exception {
        String token = identities.create().accessToken(), stranger = identities.create().accessToken();
        var resume = patchFixture(token); var batch = batch(token, resume);
        String getPath = "/resumes/" + resume.id() + "/versions/" + resume.currentVersionId() + "/patches/" + batch.batchId();
        assertThat(resumes.versions(owner(token), resume.id())).hasSize(1);
        assertThat(data(request("GET", getPath, token, null)).path("stats").path("replaced").asInt()).isEqualTo(3);
        assertThat(request("GET", getPath, stranger, null).statusCode()).isEqualTo(404);
        assertThat(request("POST", applyPath(resume), stranger, decision(batch, "p1")).statusCode()).isEqualTo(404);
        assertThat(request("POST", applyPath(resume), null, decision(batch, "p1")).statusCode()).isEqualTo(401);
        String body = "{\"batchId\":\"" + batch.batchId() + "\",\"accepted\":[\"p1\"],\"edited\":{\"p2\":{\"value\":\"本人实现登录与权限校验\"}},\"confirmed\":[\"p2\"]}";
        var applied = request("POST", applyPath(resume), token, body);
        assertThat(applied.statusCode()).isEqualTo(200);
        JsonNode version = data(applied).path("version");
        assertThat(version.path("versionNo").asInt()).isEqualTo(2);
        assertThat(version.path("source").asText()).isEqualTo("OPTIMIZED");
        assertThat(version.path("parentVersionId").asText()).isEqualTo(resume.currentVersionId());
        assertThat(version.path("parseMeta").path("batchId").asText()).isEqualTo(batch.batchId());
        assertThat(version.path("structured").at("/projects/0/bullets/0").asText()).isEqualTo("本人实现登录与权限校验");
        assertThat(version.path("structured").at("/projects/0/bullets/1").asText()).isEqualTo("优化缓存");
        assertThat(version.path("plainText").asText()).contains("本人实现登录与权限校验", "优化缓存");
        assertThat(resumes.version(owner(token), resume.id(), resume.currentVersionId()).structured()).isEqualTo(resume.currentVersion().structured());
        assertThat(data(request("GET", getPath, token, null)).path("appliedVersionId").asText()).isEqualTo(version.path("id").asText());
        // Retry is the original version, even after a subsequent manual edit.
        resumes.save(owner(token), resume.id(), version.path("id").asText(), version.path("structured"));
        assertThat(data(request("POST", applyPath(resume), token, body)).path("version")).isEqualTo(version);
        assertThat(request("POST", applyPath(resume), token, decision(batch, "p3")).statusCode()).isEqualTo(409);
        assertThat(resumes.versions(owner(token), resume.id())).hasSize(3);
    }
    @Test void patchesRejectInvalidDecisionsAndStaleVersionsWithoutPartialWrites() throws Exception {
        String token = identities.create().accessToken(); var resume = patchFixture(token); var batch = batch(token, resume);
        for (String body : List.of(decision(batch, "p2"), decision(batch, "unknown"),
                "{\"batchId\":\"" + batch.batchId() + "\",\"accepted\":[]}",
                "{\"batchId\":\"" + batch.batchId() + "\",\"accepted\":[\"p1\",\"p1\"]}",
                "{\"batchId\":\"" + batch.batchId() + "\",\"accepted\":[\"p1\"],\"extra\":true}")) {
            assertThat(request("POST", applyPath(resume), token, body).statusCode()).isEqualTo(400);
            assertThat(patches.get(owner(token), resume.id(), resume.currentVersionId(), batch.batchId()).appliedVersionId()).isNull();
            assertThat(resumes.versions(owner(token), resume.id())).hasSize(1);
        }
        var newResume = resumes.save(owner(token), resume.id(), resume.currentVersionId(), resume.currentVersion().structured());
        assertThat(request("POST", applyPath(resume), token, decision(batch, "p1")).statusCode()).isEqualTo(409);
        String wrongVersion = "/resumes/" + resume.id() + "/versions/" + newResume.currentVersionId();
        assertThat(request("GET", wrongVersion + "/patches/" + batch.batchId(), token, null).statusCode()).isEqualTo(404);
        assertThat(request("POST", wrongVersion + "/apply-patches", token, decision(batch, "p1")).statusCode()).isEqualTo(404);
        assertThat(resumes.versions(owner(token), resume.id())).hasSize(2);
        assertThat(patches.get(owner(token), resume.id(), resume.currentVersionId(), batch.batchId()).appliedVersionId()).isNull();
    }
    @Test void patchesConcurrentIdenticalRetriesCreateOnlyOneVersionAndNormalizeOrder() throws Exception {
        String token = identities.create().accessToken(); var resume = patchFixture(token); var batch = batch(token, resume);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        String body = "{\"batchId\":\"" + batch.batchId() + "\",\"accepted\":[\"p3\",\"p1\"]}";
        try {
            var a = executor.submit(() -> { start.await(); return request("POST", applyPath(resume), token, body); });
            var b = executor.submit(() -> { start.await(); return request("POST", applyPath(resume), token, body.replace("[\"p3\",\"p1\"]", "[\"p1\",\"p3\"]")); });
            start.countDown();
            var first = a.get(30, java.util.concurrent.TimeUnit.SECONDS); var second = b.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first.statusCode()).isEqualTo(200); assertThat(second.statusCode()).isEqualTo(200);
            assertThat(data(first)).isEqualTo(data(second));
            assertThat(resumes.versions(owner(token), resume.id())).hasSize(2);
        } finally { executor.shutdownNow(); }
    }
    @Test void patchesCompetingBatchesAndManualSaveHaveOnlyOneWinner() throws Exception {
        String token = identities.create().accessToken(); var resume = patchFixture(token);
        var first = batch(token, resume); var second = batch(token, resume);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(3);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var a = executor.submit(() -> { start.await(); return request("POST", applyPath(resume), token, decision(first, "p1")); });
            var b = executor.submit(() -> { start.await(); return request("POST", applyPath(resume), token, decision(second, "p3")); });
            var c = executor.submit(() -> { start.await(); return request("PUT", "/resumes/" + resume.id() + "/versions/" + resume.currentVersionId() + "/structured", token, resume.currentVersion().structured().toString()); });
            start.countDown();
            assertThat(List.of(a.get(30, java.util.concurrent.TimeUnit.SECONDS).statusCode(), b.get(30, java.util.concurrent.TimeUnit.SECONDS).statusCode(), c.get(30, java.util.concurrent.TimeUnit.SECONDS).statusCode()))
                .containsExactlyInAnyOrder(200, 409, 409);
            assertThat(resumes.versions(owner(token), resume.id())).hasSize(2);
            assertThat(resumes.get(owner(token), resume.id()).currentVersion().parentVersionId()).isEqualTo(resume.currentVersionId());
        } finally { executor.shutdownNow(); }
    }
    @Test void patchesDatabaseFailureRollsBackBothVersionAndDecision() throws Exception {
        String token = identities.create().accessToken(); var resume = patchFixture(token); var batch = batch(token, resume);
        // A fixture-only database constraint forces failure AFTER the version insert.
        jdbc.execute("ALTER TABLE resume_patch_batches ADD CONSTRAINT patch_fixture_failure CHECK (id <> '" + batch.batchId() + "' OR applied_version_id IS NULL)");
        try {
            assertThatThrownBy(() -> patches.apply(owner(token), resume.id(), resume.currentVersionId(), JSON.readTree(decision(batch, "p1"))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(resumes.get(owner(token), resume.id()).currentVersionId()).isEqualTo(resume.currentVersionId());
            assertThat(resumes.versions(owner(token), resume.id())).hasSize(1);
            assertThat(patches.get(owner(token), resume.id(), resume.currentVersionId(), batch.batchId()).appliedVersionId()).isNull();
        } finally { jdbc.execute("ALTER TABLE resume_patch_batches DROP CONSTRAINT patch_fixture_failure"); }
        assertThat(request("POST", applyPath(resume), token, decision(batch, "p1")).statusCode()).isEqualTo(200);
    }
    static final String OPTIMIZE_REPORT="""
        {"summary":"突出已有后端贡献","patches":[{"id":"p1","op":"replace","path":"/summary","from":"负责后端开发","value":"参与后端服务开发","reason":"明确职责","jdEvidence":"后端服务开发","needsUserConfirmation":false}]}
        """;
    static final String OPTIMIZE_JD="熟悉Java；具备后端服务开发能力。";
    String optimizationWithTrivialPatch() throws Exception {
        var report=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(OPTIMIZE_REPORT);
        report.withArray("patches").addObject().put("id","p2").put("op","replace")
            .put("path","/projects/0/bullets/0").put("from","实现登录").put("value","实现登录 ")
            .put("reason","调整措辞").put("jdEvidence","后端服务开发").put("needsUserConfirmation",false);
        return report.toString();
    }
    String optimizeBody(String requestId) {return JSON.createObjectNode().put("requestId",requestId).put("jobDescription",OPTIMIZE_JD).toString();}
    String chatJob(String description) {
        String id = "job_" + UUID.randomUUID().toString().replace("-", "");
        var payload = JSON.createObjectNode().put("title", "后端实习生").put("description", description).put("requirement", "");
        jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload) VALUES (?,'fixture',?,'intern',?::jsonb)", id, id, payload.toString());
        return id;
    }
    List<JsonNode> events(HttpResponse<String> response) {
        return response.body().lines().filter(line -> line.startsWith("data:")).map(line -> {
            try { return JSON.readTree(line.substring(5).strip()); } catch (Exception ex) { throw new IllegalStateException(ex); }
        }).toList();
    }
    String chatBody(String mode, String conversationId, String content, Map<String,String> context) {
        var body = JSON.createObjectNode().put("mode", mode).put("content", content);
        if (conversationId != null) body.put("conversationId", conversationId);
        body.putObject("options").put("modelTier", "FLASH").set("context", JSON.valueToTree(context));
        return body.toString();
    }
    @Test void chatRewritePersistsReadOnlyCardAndDiscussionRestoresWithoutRegeneration() throws Exception {
        String token = identities.create().accessToken(), stranger = identities.create().accessToken();
        var resume = patchFixture(token); String jobId = chatJob(OPTIMIZE_JD); output.set(optimizationWithTrivialPatch());
        var selected = Map.of("jobId", jobId, "resumeId", resume.id(), "resumeVersionId", resume.currentVersionId());
        var response = request("POST", "/agent/chat", token, chatBody("AUTO", null, "针对岗位生成改写建议", selected));
        assertThat(response.statusCode()).isEqualTo(200);
        var stream = events(response);
        var card = stream.stream().filter(e -> "ui.card".equals(e.path("type").asText())).findFirst().orElseThrow();
        assertThat(card.path("cardType").asText()).isEqualTo("resume_diff");
        assertThat(card.path("cardId").asText()).isEqualTo(card.path("payload").path("reportId").asText());
        assertThat(stream.get(stream.size()-1).path("status").asText()).isEqualTo("COMPLETED");
        String conversationId = stream.get(0).path("conversationId").asText(), reportId = card.path("cardId").asText();
        var report = optimizations.get(owner(token), reportId).path("report");
        assertThat(card.path("payload")).isEqualTo(report);
        assertThat(report.path("patches").size()).isEqualTo(1);
        assertThat(report.path("stats").path("replaced").asInt()).isEqualTo(1);
        assertThat(report.path("filteredPatches").get(0).path("id").asText()).isEqualTo("p2");
        assertThat(patches.get(owner(token), resume.id(), resume.currentVersionId(), report.path("batchId").asText()).patches().size()).isEqualTo(1);
        assertThat(resumes.get(owner(token), resume.id()).currentVersionId()).isEqualTo(resume.currentVersionId());
        assertThat(resumes.versions(owner(token), resume.id())).hasSize(1);
        assertThat(patches.get(owner(token), resume.id(), resume.currentVersionId(), report.path("batchId").asText()).appliedVersionId()).isNull();
        int calls = rewriteCalls.get(); assertThat(calls).isEqualTo(1);
        var history = data(request("GET", "/conversations/" + conversationId + "/messages", token, null));
        assertThat(history.toString()).contains("resume_diff", reportId, report.path("batchId").asText());
        assertThat(rewriteCalls.get()).isEqualTo(calls);
        var discussion = Map.of("resumeId", resume.id(), "resumeVersionId", resume.currentVersionId(), "optimizationReportId", reportId);
        var discussed = events(request("POST", "/agent/chat", token, chatBody("RESUME", conversationId, "解释已有建议", discussion)));
        assertThat(discussed).anySatisfy(e -> { assertThat(e.path("type").asText()).isEqualTo("ui.card"); assertThat(e.path("payload")).isEqualTo(report); });
        assertThat(rewriteCalls.get()).isEqualTo(calls);
        var denied = request("POST", "/agent/chat", stranger, chatBody("RESUME", null, "解释已有建议", discussion));
        assertThat(denied.body()).contains("FAILED").doesNotContain("event:ui.card", "负责后端开发");
        assertThat(rewriteCalls.get()).isEqualTo(calls);
        var newer = resumes.save(owner(token), resume.id(), resume.currentVersionId(), resume.currentVersion().structured());
        var wrongVersion = Map.of("resumeId", resume.id(), "resumeVersionId", newer.currentVersionId(), "optimizationReportId", reportId);
        assertThat(request("POST", "/agent/chat", token, chatBody("RESUME", null, "解释已有建议", wrongVersion)).body()).contains("不属于").doesNotContain("event:ui.card");
        assertThat(rewriteCalls.get()).isEqualTo(calls);
    }
    @Test void chatRewriteMissingJobContentsFailsWithoutModelOrVersionWrites() throws Exception {
        String token = identities.create().accessToken(); var resume = patchFixture(token);
        var selected = Map.of("jobId", chatJob(""), "resumeId", resume.id(), "resumeVersionId", resume.currentVersionId());
        var response = request("POST", "/agent/chat", token, chatBody("RESUME", null, "针对岗位生成改写建议", selected));
        assertThat(response.body()).contains("岗位缺少职责与要求", "FAILED").doesNotContain("event:ui.card");
        assertThat(rewriteCalls.get()).isZero();
        assertThat(optimizations.list(owner(token), resume.id())).isEmpty();
        assertThat(resumes.versions(owner(token), resume.id())).hasSize(1);
    }
    @Test void optimizePersistsSuggestionsWithoutEditingAndReplaysBeforeExplicitApply() throws Exception {
        String token=identities.create().accessToken(),stranger=identities.create().accessToken();var resume=patchFixture(token);
        output.set(optimizationWithTrivialPatch());String path=applyPath(resume).replace("apply-patches","optimize"),body=optimizeBody("optimize-request-success");
        assertThat(request("POST",path,stranger,body).statusCode()).isEqualTo(404);
        var response=request("POST",path,token,body);assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("run.started","resume_diff","run.completed");
        assertThat(lastPrompt.get()).contains("不得把「参与」改为「主导」",OPTIMIZE_JD).doesNotContain("\"basics\"");
        assertThat(resumes.versions(owner(token),resume.id())).hasSize(1);
        var tasks=data(request("GET","/resumes/"+resume.id()+"/optimizations",token,null));String reportId=tasks.get(0).path("id").asText();
        var task=data(request("GET","/resumes/optimizations/"+reportId,token,null));var report=task.path("report");
        assertThat(report.path("resumeVersionId").asText()).isEqualTo(resume.currentVersionId());
        assertThat(report.path("patches").size()).isEqualTo(1);String batchId=report.path("batchId").asText();
        assertThat(report.path("stats").path("replaced").asInt()).isEqualTo(1);
        assertThat(report.path("filteredPatches").get(0)).isEqualTo(JSON.createObjectNode()
            .put("id","p2").put("path","/projects/0/bullets/0").put("reason","NORMALIZED_EQUAL"));
        assertThat(patches.get(owner(token),resume.id(),resume.currentVersionId(),batchId).patches().size()).isEqualTo(1);
        assertThat(patches.get(owner(token),resume.id(),resume.currentVersionId(),batchId).appliedVersionId()).isNull();
        int calls=providerCalls.get();providerStatus.set(503);
        assertThat(request("POST",path,token,body).body()).contains("run.completed").doesNotContain("event:error");
        assertThat(providerCalls.get()).isEqualTo(calls);
        assertThat(request("POST",path,token,body.replace(OPTIMIZE_JD,"另一个岗位")).statusCode()).isEqualTo(400);
        assertThat(request("GET","/resumes/optimizations/"+reportId,stranger,null).statusCode()).isEqualTo(404);
        assertThat(request("POST","/resumes/optimizations/"+reportId+"/cancel",stranger,null).statusCode()).isEqualTo(404);
        var applied=request("POST",applyPath(resume),token,"{\"batchId\":\""+batchId+"\",\"accepted\":[\"p1\"]}");
        assertThat(applied.statusCode()).isEqualTo(200);assertThat(data(applied).path("version").path("source").asText()).isEqualTo("OPTIMIZED");
        assertThat(request("GET","/resumes/optimizations/"+reportId,token,null).body()).contains(batchId);
    }
    @Test void optimizeEmptyAndInvalidOutputNeverCreateSpuriousVersionsOrBatches() throws Exception {
        String token=identities.create().accessToken();var resume=patchFixture(token);String path=applyPath(resume).replace("apply-patches","optimize");
        output.set("{\"summary\":\"原文已清楚，无需改写\",\"patches\":[]}");
        assertThat(request("POST",path,token,optimizeBody("optimize-request-empty")).body()).contains("run.completed");
        var task=optimizations.get(owner(token),optimizations.list(owner(token),resume.id()).get(0).path("id").asText());
        assertThat(task.path("report").path("batchId").isNull()).isTrue();
        assertThat(task.path("report").path("filteredPatches").isArray()).isTrue();
        assertThat(task.path("report").path("filteredPatches")).isEmpty();
        output.set(OPTIMIZE_REPORT.replace("参与后端服务开发"," 负责后端开发 "));
        assertThat(request("POST",path,token,optimizeBody("optimize-request-all-filtered")).body()).contains("run.completed");
        var filtered=optimizations.get(owner(token),optimizations.list(owner(token),resume.id()).get(0).path("id").asText()).path("report");
        assertThat(filtered.path("patches")).isEmpty();
        assertThat(filtered.path("batchId").isNull()).isTrue();
        assertThat(filtered.path("stats").path("replaced").asInt()).isZero();
        assertThat(filtered.path("filteredPatches").get(0).path("reason").asText()).isEqualTo("NORMALIZED_EQUAL");
        output.set(OPTIMIZE_REPORT.replace("参与后端服务开发","提升性能60%"));int before=providerCalls.get();
        assertThat(request("POST",path,token,optimizeBody("optimize-request-invalid")).body()).contains("event:error").doesNotContain("event:ui.card");
        assertThat(providerCalls.get()-before).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resume_patch_batches WHERE user_id=?",Integer.class,owner(token))).isZero();
        assertThat(resumes.versions(owner(token),resume.id())).hasSize(1);
        assertThat(optimizations.list(owner(token),resume.id()).get(0).path("status").asText()).isEqualTo("FAILED");
    }
    @Test void optimizeRejectsUnknownInputConcurrentWorkCancelledAndExpiredResults() throws Exception {
        String token=identities.create().accessToken();var resume=patchFixture(token);String path=applyPath(resume).replace("apply-patches","optimize");
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(optimizeBody("optimize-request-fields"));input.put("jobId","guessed");
        assertThat(request("POST",path,token,input.toString()).statusCode()).isEqualTo(404);
        assertThat(request("POST",path,token,optimizeBody("short")).statusCode()).isEqualTo(400);
        var started=new java.util.concurrent.CountDownLatch(1);
        var stopped=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doReturn(reactor.core.publisher.Mono.<com.fasterxml.jackson.databind.node.ObjectNode>never()
                .doOnSubscribe(ignored -> started.countDown()).doOnCancel(stopped::countDown))
                .when(optimizer).optimize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        cancelStream(token,path,optimizeBody("optimize-request-dock"),"/resumes/"+resume.id()+"/optimizations","RESUME_OPTIMIZE",started,stopped);
        org.mockito.Mockito.reset(optimizer);
        var work=optimizations.begin(owner(token),resume.id(),resume.currentVersionId(),new ResumeOptimizationService.Input("optimize-request-cancel",OPTIMIZE_JD));
        assertThat(request("POST",path,token,optimizeBody("optimize-request-other")).statusCode()).isEqualTo(429);
        optimizations.cancel(owner(token),work.id());
        assertThat(request("POST",path,token,optimizeBody("optimize-request-cancel")).statusCode()).isEqualTo(409);
        assertThatThrownBy(() -> optimizations.execute(work).block(Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(com.offerpilot.common.error.BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(com.offerpilot.common.error.ErrorCode.GENERATION_CANCELLED));
        var expired=optimizations.begin(owner(token),resume.id(),resume.currentVersionId(),new ResumeOptimizationService.Input("optimize-request-expired",OPTIMIZE_JD));
        jdbc.update("UPDATE resume_optimizations SET created_at=now()-interval '4 minutes' WHERE id=?",expired.id());
        assertThatThrownBy(() -> optimizations.execute(expired).block(Duration.ofSeconds(3))).hasMessageContaining("过期");
        assertThat(optimizations.get(owner(token),expired.id()).path("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resume_patch_batches WHERE user_id=?",Integer.class,owner(token))).isZero();
    }
    @Test void optimizeReportSaveFailureRollsBackGeneratedBatch() throws Exception {
        String token=identities.create().accessToken();var resume=patchFixture(token);output.set(OPTIMIZE_REPORT);
        var work=optimizations.begin(owner(token),resume.id(),resume.currentVersionId(),new ResumeOptimizationService.Input("optimize-request-rollback",OPTIMIZE_JD));
        jdbc.execute("ALTER TABLE resume_optimizations ADD CONSTRAINT optimize_fixture_failure CHECK (id <> '"+work.id()+"' OR status <> 'SUCCEEDED')");
        try {
            assertThatThrownBy(() -> optimizations.execute(work).block(Duration.ofSeconds(10))).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
            assertThat(optimizations.get(owner(token),work.id()).path("status").asText()).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM resume_patch_batches WHERE user_id=?",Integer.class,owner(token))).isZero();
            assertThat(resumes.versions(owner(token),resume.id())).hasSize(1);
        } finally {jdbc.execute("ALTER TABLE resume_optimizations DROP CONSTRAINT optimize_fixture_failure");}
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
        assertThat(data(catalog).path("selection").path("textTier").asText()).isEqualTo("FLASH");
        assertThat(request("PUT", "/resume-model-settings", token, "{\"textTier\":\"FLASH\",\"visionTier\":\"PRO\"}").statusCode()).isEqualTo(400);
        assertThat(request("PUT", "/resume-model-settings", token, "{\"textTier\":\"unknown\",\"visionTier\":\"FLASH\"}").statusCode()).isEqualTo(400);
        String id = data(upload(token, "before.txt", "测试简历".getBytes(StandardCharsets.UTF_8))).path("id").asText();
        assertThat(request("PUT", "/resume-model-settings", token, "{\"textTier\":\"VISION_ALT\",\"visionTier\":\"VISION_ALT\"}").statusCode()).isEqualTo(200);
        assertThat(data(request("GET", "/resume-model-settings", token, null)).path("selection").path("textTier").asText()).isEqualTo("VISION_ALT");
        assertThat(data(request("GET", "/resume-model-settings", stranger, null)).path("selection").path("textTier").asText()).isEqualTo("FLASH");
        var queued = resumes.claim();
        assertThat(queued.id()).isEqualTo(id);
        assertThat(queued.textTier()).isEqualTo("FLASH");
        resumes.fail(queued, "模拟失败");
        resumes.retry(owner(token), id);
        var retried = resumes.claim();
        assertThat(retried.textTier()).isEqualTo("VISION_ALT");
        resumes.fail(retried, "结束夹具");
        String nextId = data(upload(token, "after.txt", "新简历".getBytes(StandardCharsets.UTF_8))).path("id").asText();
        worker.processNext();
        assertThat(resumes.get(owner(token), nextId).currentVersion().parseMeta().path("tier").asText()).isEqualTo("VISION_ALT");

        String legacyToken = identities.create().accessToken(), legacyOwner = owner(legacyToken);
        jdbc.update("INSERT INTO resume_model_preferences(user_id,text_tier,vision_tier) VALUES (?,?,?)", legacyOwner, "CHEAP", "cheap");
        var selection = data(request("GET", "/resume-model-settings", legacyToken, null)).path("selection");
        assertThat(selection.path("textTier").asText()).isEqualTo("FLASH");
        assertThat(selection.path("visionTier").asText()).isEqualTo("FLASH");
        var saved = request("PUT", "/resume-model-settings", legacyToken, "{\"textTier\":\"CHEAP\",\"visionTier\":\"CHEAP\"}");
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(data(saved).path("selection").path("textTier").asText()).isEqualTo("FLASH");
        assertThat(jdbc.queryForObject("SELECT vision_tier FROM resume_model_preferences WHERE user_id=?", String.class, legacyOwner)).isEqualTo("FLASH");

    }
}
