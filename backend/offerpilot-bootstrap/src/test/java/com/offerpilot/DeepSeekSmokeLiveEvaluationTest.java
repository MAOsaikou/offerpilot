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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import com.offerpilot.resume.application.ResumeOptimizationValidator;
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
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("offerpilot.smoke.tier", tier.name()).equals(tier.name()));
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
        var chat = chat(tier, "JOB", "请匹配这个岗位与我的简历", Map.of("jobAction", "match", "jobId", "job_demo_1", "resumeId", resumeId, "resumeVersionId", versionId));
        assertThat(cards(chat, "job_match").size()).as(tier + " chat matching").isEqualTo(1);
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
        assertThat(cards(events, "skill_gap").size()).as(tier + " coach").isEqualTo(1);
        var gap = cards(events, "skill_gap").get(0);
        assertThat(cards(events, "roadmap").get(0).path("weeks")).hasSize(4);
        String answer = narration(events);
        assertThat(List.of("只有一段报告摘要", "没有包含具体的差距", "仅收到摘要").stream().noneMatch(answer::contains)).isTrue();
        assertThat(gap.path("skills").findValuesAsText("name").stream().anyMatch(answer::contains)).isTrue();
        System.out.println("LIVE_SMOKE " + tier + " COACH digest-narration PASS");
    }

    @ParameterizedTest @EnumSource(value = ModelTier.class, names = {"FLASH", "PRO"})
    void careerSoftwareContextRanksAndNarratesItFirstWithoutVectors(ModelTier tier) throws Exception {
        var events = chat(tier, "CAREER_DISCOVERY", "我喜欢研究技术和动手搭建系统，请推荐适合我的职业，概括卡片里的前三项", Map.of());
        var recommendations = cards(events, "career_recommendation").get(0).path("recommendations");
        assertThat(recommendations.path(0).path("family").asText()).isIn("软件研发", "算法与人工智能", "测试与质量", "运维与安全", "数据", "计算机与数学");
        assertThat(recommendations.path(0).path("reasons").toString()).contains("六维 RIASEC", "职业库文本中匹配");
        assertThat(recommendations.findValuesAsText("name").stream().limit(3)).doesNotContain("假肢与矫形器师", "兽医", "口腔修复科医生");
        assertThat(narration(events).contains(recommendations.path(0).path("name").asText())).isTrue();
        assertThat(narration(events).contains("仅收到摘要")).isFalse();
        System.out.println("LIVE_SMOKE " + tier + " CAREER RIS profile-lexical-narration PASS top5=" + recommendations.findValuesAsText("name").stream().limit(5).toList());
    }

    @ParameterizedTest @EnumSource(value = ModelTier.class, names = {"FLASH", "PRO"})
    void interviewerNarratesStarAndRubricCard(ModelTier tier) throws Exception {
        String session = jdbc.queryForObject("SELECT id FROM interview_sessions WHERE user_id=? AND completed=true LIMIT 1", String.class, owner);
        var events = chat(tier, "INTERVIEWER", "请评价面试，概括评分卡的STAR和改进建议", Map.of("interviewAction", "scoreInterview", "interviewSessionId", session));
        assertThat(cards(events, "interview_report").size()).isEqualTo(1);
        assertThat(List.of("STAR", "背景", "结果").stream().anyMatch(narration(events)::contains)).isTrue();
        assertThat(List.of("只有一段报告摘要", "仅收到摘要").stream().noneMatch(narration(events)::contains)).isTrue();
        System.out.println("LIVE_SMOKE " + tier + " INTERVIEW STAR-digest-narration PASS");
    }

    @ParameterizedTest @EnumSource(value = ModelTier.class, names = {"FLASH", "PRO"})
    void resumeRewriteApprovalAndPdfContainAppliedText(ModelTier tier) throws Exception {
        // Rewrite generation follows the saved resume preference; intent/narration follow chat options.
        jdbc.update("INSERT INTO resume_model_preferences(user_id,text_tier,vision_tier) VALUES (?,?,'VISION_ALT') "
                + "ON CONFLICT(user_id) DO UPDATE SET text_tier=EXCLUDED.text_tier", owner, tier.name());
        // Save a rough draft of existing demo facts through the same editor API used by the UI.
        // The polished seed may correctly return no suggestions; that cannot exercise approval/PDF.
        var draft = (com.fasterxml.jackson.databind.node.ObjectNode) data(request("GET",
                "/api/v1/resumes/" + resumeId + "/versions/" + versionId, null)).path("structured").deepCopy();
        draft.put("summary", "我关注后端开发。接口和数据表设计我做过，预约重复的问题用数据库唯一约束和事务控制处理，还写了并发场景测试。希望在实习中学习代码评审和工程实践。以上为演示虚构内容。");
        versionId = data(request("PUT", "/api/v1/resumes/" + resumeId + "/versions/" + versionId + "/structured", draft)).path("currentVersionId").asText();
        assertThat(versionId).isNotBlank();
        int initialVersions = versionCount();
        var source = data(request("GET", "/api/v1/resumes/" + resumeId + "/versions/" + versionId, null)).path("structured");
        var events = chat(tier, "RESUME", "请针对选中的岗位生成简历改写建议，保留已有事实，突出项目里的后端开发职责。",
                Map.of("jobId", "job_demo_1", "resumeId", resumeId, "resumeVersionId", versionId));
        assertThat(cards(events, "resume_diff").size()).isEqualTo(1);
        var report = cards(events, "resume_diff").get(0);
        assertThat(report.path("modelTier").asText()).isEqualTo(tier.name());
        assertThat(report.path("model").asText()).isEqualTo(gateway.modelName(tier));
        assertThat(report.path("patches").isEmpty()).as(tier + " actionable rewrite").isFalse();
        var validated = json.createObjectNode();
        validated.set("summary", report.path("summary")); validated.set("patches", report.path("patches"));
        ResumeOptimizationValidator.validate(validated, source, JobService.evidenceText(jobs.get("job_demo_1")));
        assertThat(versionCount()).isEqualTo(initialVersions);
        var accepted = new ArrayList<String>(); var confirmed = new ArrayList<String>();
        for (var patch : report.path("patches")) {
            accepted.add(patch.path("id").asText());
            if (patch.path("needsUserConfirmation").asBoolean()) confirmed.add(patch.path("id").asText());
        }
        var context = Map.<String, Object>of("resumeAction", "applyPatches", "resumeId", resumeId,
                "resumeVersionId", versionId, "batchId", report.path("batchId").asText(),
                "accepted", accepted, "edited", Map.of(), "confirmed", confirmed);
        for (String decision : List.of("REJECT", "APPROVE")) {
            approvalChat(tier, conversation(events), "RESUME", "请采纳所选的简历改写建议", context, decision,
                    () -> assertThat(versionCount()).isEqualTo(initialVersions));
            assertThat(versionCount()).isEqualTo(initialVersions + (decision.equals("APPROVE") ? 1 : 0));
        }
        String newVersion = jdbc.queryForObject("SELECT current_version_id FROM resumes WHERE id=?", String.class, resumeId);
        assertThat(newVersion).isNotEqualTo(versionId);
        var saved = data(request("GET", "/api/v1/resumes/" + resumeId + "/versions/" + newVersion, null));
        assertThat(saved.path("parentVersionId").asText()).isEqualTo(versionId);
        assertThat(data(request("GET", "/api/v1/resumes/" + resumeId + "/versions/" + versionId, null)).path("structured")).isEqualTo(source);
        var pdf = HttpClient.newHttpClient().send(requestBuilder("GET", "/api/v1/resumes/" + resumeId + "/export.pdf?template=classic", null).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Content-Type").orElse("")).contains("application/pdf");
        assertThat(new String(pdf.body(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        try (var document = Loader.loadPDF(pdf.body())) {
            assertThat(document.getNumberOfPages()).isGreaterThan(0);
            String extracted = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
            for (var patch : report.path("patches")) {
                assertThat(saved.path("structured").at(patch.path("path").asText()).equals(patch.path("value"))).as("saved patch equals selected value").isTrue();
                assertThat(extracted.contains(patch.path("value").asText().replaceAll("\\s+", ""))).as("PDF contains patched text").isTrue();
            }
        }
        System.out.println("LIVE_SMOKE " + tier + " A rewrite/reject/approve/saved-version/PDF PASS patches=" + accepted.size());
    }

    @ParameterizedTest @EnumSource(value = ModelTier.class, names = {"FLASH", "PRO"})
    void boardExplicitAndModelInferredApprovalThroughHttp(ModelTier tier) throws Exception {
        var matched = chat(tier, "JOB", "请匹配这个岗位与我的简历", Map.of("jobAction", "match", "jobId", "job_demo_1", "resumeId", resumeId, "resumeVersionId", versionId));
        assertThat(cards(matched, "job_match").size()).isEqualTo(1);
        String conversation = conversation(matched);
        for (String decision : List.of("REJECT", "APPROVE")) {
            clearBoardJob();
            approvalChat(tier, conversation, "AUTO", "把这个岗位加入求职看板",
                    Map.of("jobId", "job_demo_1", "boardAction", "createApplication"), decision,
                    () -> assertThat(boardCount()).isZero());
            verifyBoard(decision);
        }
        // The exact UI sentence is deterministic even without boardAction (#47).
        clearBoardJob();
        approvalChat(tier, conversation, "AUTO", "把这个岗位加入求职看板", Map.of("jobId", "job_demo_1"), "REJECT",
                () -> assertThat(boardCount()).isZero());
        verifyBoard("REJECT");
        // All three requests bypass BoardIntent's narrow whole-sentence pattern.
        var requests = List.of("我想跟进当前选中的职位，请在我的看板里新建一条待投递记录。",
                "这个职位我打算申请，请帮我建一个求职跟踪记录，先设为待投递。",
                "把当前选择的职位记录到求职看板，我之后再投递。");
        int passed = 0;
        for (int i = 0; i < requests.size(); i++) {
            clearBoardJob();
            String decision = i == 2 ? "REJECT" : "APPROVE";
            approvalChat(tier, conversation, "AUTO", requests.get(i), Map.of("jobId", "job_demo_1"), decision,
                    () -> assertThat(boardCount()).isZero());
            verifyBoard(decision); passed++;
            System.out.println("LIVE_SMOKE " + tier + " B inferred attempt=" + (i + 1) + " PASS decision=" + decision);
        }
        System.out.println("LIVE_SMOKE " + tier + " B explicit/exact-text PASS inferred=" + passed + "/3");
    }

    @ParameterizedTest @EnumSource(value = ModelTier.class, names = {"FLASH", "PRO"})
    void autoChatSearchFallsBackAndNarratesOfflineSource(ModelTier tier) throws Exception {
        var events = chat(tier, "AUTO", "请找 Java 后端实习岗位，不限城市，说明数据来源。", Map.of());
        assertThat(cards(events, "job_card").size()).isEqualTo(1);
        var card = cards(events, "job_card").get(0);
        assertThat(card.path("items").isEmpty()).isFalse();
        assertThat(card.path("meta").path("source").asText()).isEqualTo("LOCAL_SNAPSHOT");
        assertThat(card.path("meta").path("snapshotDate").asText()).isNotBlank();
        assertThat(card.path("meta").path("fallbackReason").asText()).isNotBlank();
        verify(liveJobs, atLeastOnce()).call(eq("search_jobs"), anyMap());
        String answer = narration(events);
        assertThat(List.of("离线", "快照", "非实时").stream().anyMatch(answer::contains)).isTrue();
        assertThat(List.of("实时查询到", "实时搜索到", "官网当前在招", "最新在招").stream().noneMatch(answer::contains)).isTrue();
        System.out.println("LIVE_SMOKE " + tier + " C AUTO offline-job-card/source/narration PASS count=" + card.path("items").size());
    }

    private int versionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM resume_versions WHERE resume_id=?", Integer.class, resumeId);
    }
    private void clearBoardJob() {
        jdbc.update("DELETE FROM applications WHERE user_id=? AND job_id='job_demo_1'", owner);
    }
    private int boardCount() {
        return jdbc.queryForObject("SELECT count(*) FROM applications WHERE user_id=? AND job_id='job_demo_1'", Integer.class, owner);
    }
    private void verifyBoard(String decision) throws Exception {
        int expected = decision.equals("APPROVE") ? 1 : 0;
        assertThat(boardCount()).isEqualTo(expected);
        var applications = data(request("GET", "/api/v1/applications", null));
        assertThat(applications.findValuesAsText("jobId").stream().filter("job_demo_1"::equals).count()).isEqualTo(expected);
    }
    private String conversation(List<JsonNode> events) {
        return events.stream().filter(e -> e.path("type").asText().equals("run.started")).findFirst().orElseThrow().path("conversationId").asText();
    }
    private List<JsonNode> approvalChat(ModelTier tier, String conversation, String mode, String content,
                                       Map<String, Object> context, String decision, Runnable beforeApproval) throws Exception {
        var body = Map.of("conversationId", conversation, "content", content, "mode", mode,
                "options", Map.of("modelTier", tier.name(), "context", context));
        var response = HttpClient.newHttpClient().send(requestBuilder("POST", "/api/v1/agent/chat", body).build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        var events = new ArrayList<JsonNode>();
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            JsonNode approval = null;
            for (String line; (line = reader.readLine()) != null;) {
                if (!line.startsWith("data:")) continue;
                var event = json.readTree(line.substring(5).strip()); events.add(event);
                if (event.path("type").asText().equals("approval.required")) { approval = event; break; }
            }
            if (approval == null) java.nio.file.Files.writeString(java.nio.file.Path.of(
                    "/tmp/offerpilot-deepseek-missing-approval-" + tier + ".json"), json.writeValueAsString(events));
            assertThat(approval != null).as(tier + " approval required for " + content).isTrue();
            assertThat(events.stream().anyMatch(e -> e.path("type").asText().equals("tool.call") && e.path("access").asText().equals("WRITE"))).isTrue();
            beforeApproval.run();
            assertThat(request("POST", "/api/v1/agent/runs/" + approval.path("runId").asText() + "/approvals/" + approval.path("approvalId").asText(),
                    Map.of("decision", decision)).statusCode()).isEqualTo(200);
            for (String line; (line = reader.readLine()) != null;) if (line.startsWith("data:")) events.add(json.readTree(line.substring(5).strip()));
        }
        verifyCompleted(events);
        assertThat(events.stream().anyMatch(e -> e.path("type").asText().equals("approval.resolved") && e.path("decision").asText().equals(decision))).isTrue();
        assertThat(events.stream().anyMatch(e -> e.path("type").asText().equals("tool.result") && e.path("ok").asBoolean() == decision.equals("APPROVE"))).isTrue();
        return events;
    }
    private void verifyCompleted(List<JsonNode> events) {
        assertThat(events.stream().filter(e -> e.path("type").asText().equals("error")).map(e -> e.path("code").asText()).toList()).isEmpty();
        assertThat(events.stream().anyMatch(e -> e.path("type").asText().equals("run.completed"))).isTrue();
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
        if ("JOB".equals(mode) && context.containsKey("resumeVersionId")) {
            var capture = java.nio.file.Files.createTempFile("offerpilot-deepseek-match-" + tier + "-", ".json");
            java.nio.file.Files.writeString(capture, json.writeValueAsString(events));
        }
        verifyCompleted(events);
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
        return HttpClient.newHttpClient().send(requestBuilder(method, path, body).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder requestBuilder(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(240))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return builder;
    }
}
