package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.DemoAccountBootstrap;
import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.job.application.JobService;
import com.offerpilot.knowledge.application.OccupationEmbeddings;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import com.offerpilot.mcp.JobsMcpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Paid opt-in: host JVM, prod wiring, isolated demo DB/Redis and memory source files, no embeddings.
 * Only jobs MCP is stubbed unavailable, so demo/snapshot/cache policy cannot depend on the network. */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "OFFERPILOT_RUN_LIVE_SMOKE", matches = "true")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".*\\S.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.openai.api-key=sk-not-configured", "spring.ai.model.embedding=openai",
        "offerpilot.security.jwt-secret=live-smoke-isolated-signing-key-not-for-production-0123456789abcdef0123456789abcdef",
        "offerpilot.demo.enabled=true", "offerpilot.demo.email=smoke-demo@example.test",
        "offerpilot.demo.password=fictional-smoke-demo-password", "offerpilot.demo.reset-cron=-",
        "offerpilot.resume.worker-enabled=false", "spring.ai.mcp.server.enabled=false",
        "offerpilot.staff-bootstrap.email=", "offerpilot.staff-bootstrap.password=",
        "offerpilot.jobs.source-mode=AUTO", "offerpilot.knowledge.occupation-embed.enabled=false",
        "spring.ai.retry.max-attempts=1"
})
@ActiveProfiles("prod")
@Testcontainers
@Import(DemoJourneyIntegrationTest.StorageConfig.class)
class DeepSeekSmokeLiveEvaluationTest {
    @Container static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIp("127.0.0.1"), new ExposedPort(5432))));
    @Container static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIp("127.0.0.1"), new ExposedPort(6379))));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.ai.deepseek.api-key", () -> System.getenv("DEEPSEEK_API_KEY"));
    }
    @SpyBean ModelGateway gateway;
    @MockBean JobsMcpClient liveJobs;
    @Autowired ObjectMapper json;
    @Autowired PasswordLoginService identities;
    @Autowired DemoAccountBootstrap accounts;
    @Autowired OccupationEmbeddings embeddings;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobService jobs;
    @LocalServerPort int port;
    String token;
    String owner;
    String resumeId;
    String versionId;

    @BeforeEach void demo() {
        owner = accounts.requireAccount();
        token = accounts.login(identities).accessToken();
        var resume = jdbc.queryForMap("SELECT id,current_version_id FROM resumes WHERE user_id=? ORDER BY id LIMIT 1", owner);
        resumeId = resume.get("id").toString(); versionId = resume.get("current_version_id").toString();
        when(liveJobs.call(anyString(), anyMap())).thenThrow(new BusinessException(ErrorCode.TOOL_FAILED));
        assertThat(embeddings.embed(List.of("Java 后端开发"))).isEmpty();
    }

    @ParameterizedTest @EnumSource(value = ModelTier.class, names = {"FLASH", "PRO"})
    void matchesDemoAndStoredJobsWithVerifiedEvidenceThroughRestAndChat(ModelTier tier) throws Exception {
        doReturn(tier).when(gateway).defaultTier(); // REST has no tier parameter; exercise each deployment default.
        jdbc.update("UPDATE jobs SET detail_fetched_at=now()-interval '1 hour' WHERE site='demo'");
        assertThat(data(request("GET", "/api/v1/jobs/job_demo_1", null)).path("source").asText()).isEqualTo("demo");
        assertThat(data(request("POST", "/api/v1/jobs/job_demo_1/refresh", Map.of())).path("source").asText()).isEqualTo("demo");
        var report = data(request("POST", "/api/v1/jobs/job_demo_1/matches", Map.of("resumeId", resumeId,
                "resumeVersionId", versionId, "requestId", UUID.randomUUID().toString())));
        verifyReport(report);
        assertThat(report.path("model").asText()).isEqualTo(gateway.modelName(tier));
        verifyNoInteractions(liveJobs);
        System.out.println("LIVE_SMOKE " + tier + " demo-expired REST-match PASS verified=" + report.path("requirements").size());
        var chat = chat(tier, "JOB", "请匹配这个岗位与我的简历", Map.of("jobId", "job_demo_1", "resumeId", resumeId, "resumeVersionId", versionId));
        assertThat(cards(chat, "job_match")).as(tier + " chat matching; events=" + chat).hasSize(1);
        verifyNoInteractions(liveJobs);
        System.out.println("LIVE_SMOKE " + tier + " demo-expired chat-match PASS");

        var stored = (com.fasterxml.jackson.databind.node.ObjectNode)jobs.get("job_demo_1").deepCopy();
        stored.put("site", "tencent").put("externalId", "smoke-stored").put("source", "company-career-site");
        jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload) VALUES ('job_smoke_stored','tencent','smoke-stored','intern',?::jsonb) ON CONFLICT (id) DO UPDATE SET payload=EXCLUDED.payload", stored.toString());
        assertThat(data(request("POST", "/api/v1/jobs/job_smoke_stored/refresh", Map.of())).path("source").asText()).isEqualTo("STORED_SNAPSHOT");
        // Check the snapshot policy independently; real-model matching already exercised above.
        assertThat(jobs.detail("job_smoke_stored", false).path("fallbackReason").asText()).isEqualTo("LIVE_DETAIL_UNAVAILABLE");
        var local = jobs.search("Java", "", "intern", "");
        assertThat(local.path("items")).isNotEmpty();
        assertThat(jobs.detail(local.path("items").get(0).path("id").asText(), true).path("source").asText()).isEqualTo("LOCAL_SNAPSHOT");
        System.out.println("LIVE_SMOKE " + tier + " AUTO stored-detail and LOCAL_SNAPSHOT PASS");
    }

    @ParameterizedTest @EnumSource(value = ModelTier.class, names = {"FLASH", "PRO"})
    void coachNarratesTheGapsAndFourWeekRoadmap(ModelTier tier) throws Exception {
        var events = chat(tier, "COACH", "请分析技能差距并给出四周学习路线，概括卡片里的重点", Map.of("resumeId", resumeId, "resumeVersionId", versionId));
        assertThat(cards(events, "skill_gap")).as(tier + " coach; events=" + events).hasSize(1);
        var gap = cards(events, "skill_gap").get(0);
        assertThat(cards(events, "roadmap").get(0).path("weeks")).hasSize(4);
        String answer = narration(events);
        assertThat(answer).doesNotContain("只有一段报告摘要", "没有包含具体的差距", "仅收到摘要");
        assertThat(gap.path("skills")).anySatisfy(skill -> assertThat(answer).contains(skill.path("name").asText()));
        System.out.println("LIVE_SMOKE " + tier + " COACH digest-narration PASS");
    }

    @ParameterizedTest @EnumSource(value = ModelTier.class, names = {"FLASH", "PRO"})
    void careerSoftwareContextRanksAndNarratesItFirstWithoutVectors(ModelTier tier) throws Exception {
        var events = chat(tier, "CAREER_DISCOVERY", "我喜欢研究技术和动手搭建系统，请推荐适合我的职业，概括卡片里的前三项", Map.of());
        var recommendations = cards(events, "career_recommendation").get(0).path("recommendations");
        assertThat(recommendations.path(0).path("family").asText()).isIn("软件研发", "算法与人工智能", "测试与质量", "运维与安全", "数据", "计算机与数学");
        assertThat(recommendations.path(0).path("reasons").toString()).contains("六维 RIASEC", "职业库文本中匹配");
        assertThat(recommendations.findValuesAsText("name").stream().limit(3)).doesNotContain("假肢与矫形器师", "兽医", "口腔修复科医生");
        assertThat(narration(events)).contains(recommendations.path(0).path("name").asText()).doesNotContain("仅收到摘要");
        System.out.println("LIVE_SMOKE " + tier + " CAREER RIS profile-lexical-narration PASS top5=" + recommendations.findValuesAsText("name").stream().limit(5).toList());
    }

    @ParameterizedTest @EnumSource(value = ModelTier.class, names = {"FLASH", "PRO"})
    void interviewerNarratesStarAndRubricCard(ModelTier tier) throws Exception {
        String session = jdbc.queryForObject("SELECT id FROM interview_sessions WHERE user_id=? AND completed=true LIMIT 1", String.class, owner);
        var events = chat(tier, "INTERVIEWER", "请评价面试，概括评分卡的STAR和改进建议", Map.of("interviewAction", "scoreInterview", "interviewSessionId", session));
        assertThat(cards(events, "interview_report")).hasSize(1);
        assertThat(narration(events)).containsAnyOf("STAR", "背景", "结果").doesNotContain("只有一段报告摘要", "仅收到摘要");
        System.out.println("LIVE_SMOKE " + tier + " INTERVIEW STAR-digest-narration PASS");
    }

    private void verifyReport(JsonNode report) {
        assertThat(report.path("requirements").size()).isBetween(1, 12);
        String jd = JobService.evidenceText(report.path("jobSnapshot"));
        int points = 0;
        for (var item : report.path("requirements")) {
            assertThat(jd).contains(item.path("jdQuote").asText());
            if (item.path("status").asText().equals("MISSING")) {
                assertThat(item.path("resumePath").asText()).isEmpty(); assertThat(item.path("resumeQuote").asText()).isEmpty();
            } else {
                assertThat(report.path("resumeSnapshot").at(item.path("resumePath").asText()).asText()).contains(item.path("resumeQuote").asText());
                points += item.path("status").asText().equals("MET") ? 2 : 1;
            }
        }
        assertThat(report.path("score").asInt()).isEqualTo(Math.round(100f * points / (2 * report.path("requirements").size())));
    }
    private List<JsonNode> chat(ModelTier tier, String mode, String content, Map<String, Object> context) throws Exception {
        var response = request("POST", "/api/v1/agent/chat", Map.of("content", content, "mode", mode,
                "options", Map.of("modelTier", tier.name(), "context", context)));
        assertThat(response.statusCode()).isEqualTo(200);
        var events = new ArrayList<JsonNode>();
        for (String line : response.body().split("\\R")) if (line.startsWith("data:")) events.add(json.readTree(line.substring(5).strip()));
        assertThat(events).noneSatisfy(event -> assertThat(event.path("type").asText()).isEqualTo("error"));
        assertThat(events).anySatisfy(event -> assertThat(event.path("type").asText()).isEqualTo("run.completed"));
        return events;
    }
    private List<JsonNode> cards(List<JsonNode> events, String type) {
        return events.stream().filter(e -> e.path("type").asText().equals("ui.card") && e.path("cardType").asText().equals(type)).map(e -> e.path("payload")).toList();
    }
    private String narration(List<JsonNode> events) {
        return events.stream().filter(e -> e.path("type").asText().equals("text.delta")).map(e -> e.path("text").asText()).reduce("", String::concat);
    }
    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }
    private HttpResponse<String> request(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(240))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
