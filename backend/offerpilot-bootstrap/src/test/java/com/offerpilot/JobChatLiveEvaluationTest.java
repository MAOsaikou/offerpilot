package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.ExpertType;
import com.offerpilot.agent.domain.PlanStep;
import com.offerpilot.agent.orchestrator.JobIntentParser;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式启用的真实模型/MCP小样本验收；数据库隔离，仅生成虚构简历。 */
@EnabledIfEnvironmentVariable(named = "OFFERPILOT_RUN_LIVE_JOBS", matches = "true")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".*\\S.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=deepseek", "spring.ai.model.embedding=none", "spring.ai.retry.max-attempts=1",
        "offerpilot.llm.default-tier=FLASH", "spring.ai.mcp.server.enabled=false",
        "offerpilot.resume.worker-enabled=false", "logging.level.com.offerpilot=INFO"})
@ActiveProfiles("test")
@Testcontainers
class JobChatLiveEvaluationTest {
    @Container static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("offerpilot.mcp.jobs-url", () -> System.getenv().getOrDefault("OFFERPILOT_LIVE_JOBS_URL", "http://127.0.0.1:7001"));
    }
    @Autowired JobIntentParser parser;
    @Autowired GuestIdentityService identities;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @Test void realParametersSearchPersistenceAndSelectedVersionMatch() throws Exception {
        var summary = json.createObjectNode();
        long began = System.nanoTime();
        var step = new PlanStep("s1", "岗位分析", ExpertType.JOB, Map.of(), List.of());
        var corrected = parser.prepare(step, "城市换成北京，其他条件不变", List.of(new UserMessage("找上海Java校招岗位")),
                ModelTier.FLASH, ignored -> {}, Map.of()).block(Duration.ofSeconds(75));
        assertThat(corrected.inputs()).containsEntry("action", "search").containsEntry("nature", "campus").containsEntry("location", "北京");
        assertThat(String.valueOf(corrected.inputs().get("query")).toLowerCase(Locale.ROOT)).contains("java");
        summary.set("followUpCriteria", json.valueToTree(corrected.inputs()));
        var clarification = parser.prepare(step, "帮我找工作", List.of(), ModelTier.FLASH, ignored -> {}, Map.of()).block(Duration.ofSeconds(75));
        assertThat(clarification.inputs()).containsEntry("action", "clarify");
        summary.put("clarifiesMissingKeyword", true);

        String token = identities.create().accessToken();
        long searchStart = System.nanoTime();
        var search = events(request("POST", "/agent/chat", token, Map.of("mode", "AUTO", "content", "帮我找滴滴的 Java 校招岗位，不限城市。")));
        var card = card(search, "job_card");
        var payload = card.path("payload");
        assertThat(payload.path("criteria").path("site").asText()).isEqualTo("didi");
        assertThat(payload.path("criteria").path("nature").asText()).isEqualTo("campus");
        assertThat(payload.path("criteria").path("location").asText()).isEmpty();
        assertThat(payload.path("items").size()).as("真实岗位结果须非空才能继续匹配验收").isGreaterThan(0);
        String conversation = search.get(0).path("conversationId").asText();
        var restored = json.readTree(request("GET", "/conversations/" + conversation + "/messages", token, null)).path("data").path("items");
        assertThat(restored.toString()).contains(card.path("cardId").asText());
        summary.put("searchChatMs", elapsed(searchStart));
        summary.set("searchSnapshot", payload);

        String owner = identities.require("Bearer " + token), resume = UUID.randomUUID().toString();
        String structured = "{\"summary\":\"软件工程本科应届生，完成 Java 和 Spring Boot 校园图书管理项目，使用 MySQL 保存借阅记录，编写接口测试。\"}";
        jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES (?,?,'RESUME','test',?,'fictional.txt','text/plain',1,'test')", resume, owner, resume);
        jdbc.update("INSERT INTO resumes(id,user_id,title,source_file_id,parse_status) VALUES (?,?,'虚构岗位验收简历',?,'READY')", resume, owner, resume);
        jdbc.update("INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text) VALUES (?,?,?,1,'UPLOAD',?::jsonb,'虚构验收')", resume, resume, owner, structured);
        jdbc.update("UPDATE resumes SET current_version_id=? WHERE id=?", resume, resume);
        String job = payload.path("items").get(0).path("id").asText();
        long matchStart = System.nanoTime();
        var matched = events(request("POST", "/agent/chat", token, Map.of("mode", "JOB", "conversationId", conversation,
                "content", "请将选定岗位与这份已保存的简历版本进行匹配，说明原文证据与差距。",
                "options", Map.of("context", Map.of("jobId", job, "resumeId", resume, "resumeVersionId", resume)))));
        var report = card(matched, "job_match");
        assertThat(report.path("payload").path("resumeVersionId").asText()).isEqualTo(resume);
        assertThat(jdbc.queryForObject("SELECT status FROM job_matches WHERE id=?", String.class, report.path("cardId").asText())).isEqualTo("COMPLETED");
        summary.put("matchChatMs", elapsed(matchStart));
        summary.set("matchReport", report.path("payload"));
        summary.put("totalMs", elapsed(began));
        summary.put("scope", "真实模型小样本与公开MCP端到端冒烟，不代表整体质量或P95达标");
        Files.writeString(Path.of("target", "job-chat-live-evaluation.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    }

    private String request(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofMinutes(4)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage("HTTP状态：%s", response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private List<JsonNode> events(String body) {
        return body.lines().filter(line -> line.startsWith("data:")).map(line -> {
            try { return json.readTree(line.substring(5).strip()); }
            catch (Exception ex) { throw new IllegalStateException("事件解析失败"); }
        }).toList();
    }

    private JsonNode card(List<JsonNode> events, String type) {
        assertThat(events.stream().anyMatch(e -> "run.completed".equals(e.path("type").asText()) && "COMPLETED".equals(e.path("status").asText())))
                .withFailMessage("运行未完成，事件状态：%s", events.stream().map(e -> e.path("type").asText()).toList()).isTrue();
        return events.stream().filter(e -> "ui.card".equals(e.path("type").asText()) && type.equals(e.path("cardType").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("未收到 " + type + "；工具结果：" + events.stream()
                        .filter(e -> "tool.result".equals(e.path("type").asText())).map(e -> e.path("resultPreview").asText()).toList()));
    }

    private long elapsed(long start) { return Duration.ofNanos(System.nanoTime() - start).toMillis(); }
}
