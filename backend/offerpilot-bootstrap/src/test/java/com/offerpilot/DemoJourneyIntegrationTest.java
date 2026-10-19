package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.DemoAccountBootstrap;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.demo.DemoJourneyBootstrap;
import com.offerpilot.demo.DemoResetService;
import com.offerpilot.resume.application.ResumeDocumentReader;
import com.offerpilot.resume.application.ResumeObjectStore;
import com.offerpilot.resume.application.ResumeStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none", "spring.ai.mcp.server.enabled=false",
        "offerpilot.demo.enabled=true", "offerpilot.demo.email=demo@offerpilot.local",
        "offerpilot.demo.password=isolated-demo-test-password", "offerpilot.demo.reset-cron=-",
        "offerpilot.demo.sandbox-max-active=2", "offerpilot.demo.sandbox-max-per-day=3",
        "offerpilot.demo.sandbox-daily-token-limit=150000", "offerpilot.demo.sandbox-cleanup-delay-ms=3600000",
        "offerpilot.privacy.cleanup-delay-ms=3600000", "offerpilot.rate-limit.auth-demo-login-per-minute=100",
        "offerpilot.staff-bootstrap.email=", "offerpilot.staff-bootstrap.password="
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Testcontainers
@Import(DemoJourneyIntegrationTest.StorageConfig.class)
class DemoJourneyIntegrationTest {
    @Container static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DemoAccountBootstrap accounts;
    @Autowired DemoJourneyBootstrap bootstrap;
    @Autowired DemoResetService resets;
    @Autowired com.offerpilot.demo.DemoSandboxService sandboxes;
    @Autowired com.offerpilot.auth.application.PasswordLoginService logins;
    @Autowired com.offerpilot.llm.quota.LlmQuotaLimiter limiter;
    @Autowired com.offerpilot.llm.quota.LlmQuotaIdentity quotas;
    @Autowired com.offerpilot.llm.quota.LlmUsageStore usage;
    @Autowired com.offerpilot.privacy.AccountDataCleanup cleanup;
    @Autowired com.offerpilot.conversation.application.ConversationService conversations;
    @Autowired com.offerpilot.agent.application.ApprovalService approvals;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ResumeObjectStore storage;
    @Autowired ResumeDocumentReader reader;
    @Autowired com.offerpilot.knowledge.application.KnowledgeNoteService notes;
    @LocalServerPort int port;

    @TestConfiguration static class StorageConfig {
        @Bean @Primary ResumeObjectStore demoStorage() { return new MemoryDemoStorage(); }
    }

    static class MemoryDemoStorage implements ResumeObjectStore {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        boolean failWrites;
        Runnable beforePut = () -> {};
        public String bucket() { return "isolated-demo-fixtures"; }
        public void put(String key, byte[] bytes, String mime) {
            beforePut.run();
            if (failWrites) throw new com.offerpilot.common.error.BusinessException(com.offerpilot.common.error.ErrorCode.STORAGE_UNAVAILABLE);
            objects.put(key, bytes);
        }
        public byte[] get(String bucket, String key) { return objects.get(key); }
        public void remove(String bucket, String key) { objects.remove(key); }
    }

    @BeforeEach void freshJourney() {
        jdbc.execute("TRUNCATE users CASCADE");
        jdbc.execute("TRUNCATE demo_sandbox_admissions, account_data_cleanup");
        ((MemoryDemoStorage) storage).objects.clear();
        resets.seedIfEmpty(accounts.requireAccount());
    }

    @Test void resetRestoresFictionalMemoriesAndOptIn() {
        String owner = owner();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_memories WHERE user_id=?", Integer.class, owner)).isEqualTo(3);
        jdbc.update("DELETE FROM user_memories WHERE user_id=?", owner);
        jdbc.update("UPDATE user_preferences SET memory_enabled=false WHERE user_id=?", owner);
        var schedule=jdbc.queryForList("SELECT id,next_action,next_action_at-current_date AS day_offset FROM applications WHERE user_id=? ORDER BY id",owner);
        assertThat(resets.reset().memoryCount()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT id,next_action,next_action_at-current_date AS day_offset FROM applications WHERE user_id=? ORDER BY id",owner)).isEqualTo(schedule);
        assertThat(schedule.stream().map(row -> row.get("day_offset")).toList()).containsExactly(0,-1,3,1,5,null);
        assertThat(schedule.stream().map(row -> row.get("next_action")).filter(value -> !"".equals(value)).toList()).hasSize(5).doesNotHaveDuplicates();
        assertThat(jdbc.queryForList("SELECT fact FROM user_memories WHERE user_id=?", String.class, owner))
                .hasSize(3).allSatisfy(fact -> assertThat(fact).contains("虚构演示"));
        assertThat(jdbc.queryForObject("SELECT memory_enabled FROM user_preferences WHERE user_id=?", Boolean.class, owner)).isTrue();
    }

    private String owner() {
        return jdbc.queryForObject("SELECT id FROM users WHERE email='demo@offerpilot.local'", String.class);
    }

    @Test void provisionsUserAndRealSourcesAndOneClickLoginReadsFullJourney() throws Exception {
        var account = jdbc.queryForMap("SELECT * FROM users WHERE id=?", owner());
        assertThat(account.get("is_demo")).isEqualTo(true);
        assertThat(account.get("status")).isEqualTo("ACTIVE");
        assertThat(passwords.matches("isolated-demo-test-password", account.get("password_hash").toString())).isTrue();
        assertThat(jdbc.queryForObject("SELECT default_model_tier FROM user_preferences WHERE user_id=?", String.class, owner())).isEqualTo("FLASH");
        var login = request("POST", "/auth/demo-login", null, null);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().firstValue("Cache-Control")).contains("no-store");
        var data = json.readTree(login.body()).path("data");
        assertThat(data.path("user").path("isDemo").asBoolean()).isTrue();
        assertThat(data.path("user").path("roles").get(0).asText()).isEqualTo("USER");
        assertThat(data.path("expiresIn").asInt()).isEqualTo(43200);
        String token = data.path("accessToken").asText();
        assertThat(token).isNotBlank();
        var resumes = request("GET", "/resumes", token, null);
        assertThat(resumes.statusCode()).isEqualTo(200);
        var items = json.readTree(resumes.body()).path("data").path("items");
        assertThat(items.size()).isEqualTo(2);
        for (var resume : items) {
            assertThat(resume.path("parseStatus").asText()).isEqualTo("READY");
            var detail = request("GET", "/resumes/" + resume.path("id").asText(), token, null);
            ResumeStructure.validate(json.readTree(detail.body()).path("data").path("currentVersion").path("structured"));
        }
        for (var file : jdbc.queryForList("SELECT bucket,object_key,mime,size FROM files WHERE user_id=?", data.path("user").path("id").asText())) {
            var bytes = storage.get(file.get("bucket").toString(), file.get("object_key").toString());
            assertThat(bytes.length).isEqualTo(((Number) file.get("size")).intValue());
            assertThat(reader.read(bytes, file.get("mime").toString()).text()).contains("校园", "虚构", "Java");
        }
        assertThat(json.readTree(request("GET", "/applications", token, null).body()).path("data").size()).isEqualTo(6);
        assertThat(json.readTree(request("GET", "/interviews", token, null).body()).path("data").size()).isEqualTo(2);
        assertThat(json.readTree(request("GET", "/knowledge/notes", token, null).body()).path("data").path("total").asInt()).isEqualTo(3);
        assertThat(json.readTree(request("GET", "/knowledge/notes/search?q=缓存", token, null).body()).path("data").path("items")).isNotEmpty();
        assertThat(json.readTree(request("GET", "/career/profile", token, null).body()).path("data").path("target").asText()).isEqualTo("后端开发工程师");
    }

    @Test void resetsAreIdempotentAndPreserveOtherUsersAndRealJobs() throws Exception {
        jdbc.update("INSERT INTO users(id,email,password_hash,nickname) VALUES ('usr_other','other@example.edu.cn','fixture-unusable','其他用户')");
        jdbc.update("INSERT INTO applications(id,user_id,job_id,note) VALUES ('app_other','usr_other','job_demo_1','保留其他用户')");
        jdbc.update("INSERT INTO career_profiles(user_id,target) VALUES ('usr_other','其他用户目标')");
        jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload) VALUES ('job_real','real-site','real-1','intern','{}')");
        jdbc.update("INSERT INTO applications(id,user_id,job_id) VALUES ('app_demo_added',?,'job_real')", owner());
        jdbc.update("INSERT INTO conversations(id,user_id,title) VALUES ('conv_demo',?,'待清理')", owner());
        jdbc.update("INSERT INTO career_assessments(user_id,answers) VALUES (?,'[\"上一位访客的回答\"]'::jsonb)", owner());
        jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload,user_id) VALUES ('job_demo_import','imported','prev-visitor','campus','{}',?)", owner());
        jdbc.update("INSERT INTO applications(id,user_id,job_id) VALUES ('app_demo_import',?,'job_demo_import')", owner());
        var draft = new com.offerpilot.knowledge.application.KnowledgeNoteService.Draft("其他用户笔记", "保留其他用户的原文", "技术积累");
        String otherNote = notes.create("usr_other", draft).id();
        notes.importNotes(owner(), java.util.List.of(new com.offerpilot.knowledge.application.KnowledgeNoteService.ImportEntry("old-demo", draft)));
        int occupations = jdbc.queryForObject("SELECT count(*) FROM occupations", Integer.class);
        String id = owner();
        jdbc.update("UPDATE career_profiles SET major='上一位专业',courses='上一位课程',values_text='上一位价值观' WHERE user_id=?", id);
        jdbc.update("INSERT INTO user_memories(id,user_id,kind,fact,normalized_fact,source,tsv) VALUES ('cd03-demo-memory',?,'OTHER','专业：上一位专业','专业：上一位专业','MANUAL','')", id);
        var first = resets.reset();
        var second = resets.reset();
        assertThat(first.resumeCount()).isEqualTo(2);
        assertThat(second.applicationCount()).isEqualTo(6);
        assertThat(second.jobCount()).isEqualTo(10);
        assertThat(second.knowledgeNoteCount()).isEqualTo(3);
        assertThat(jdbc.queryForMap("SELECT major,courses,values_text FROM career_profiles WHERE user_id=?", id))
                .containsEntry("major", "").containsEntry("courses", "").containsEntry("values_text", "");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_memories WHERE user_id=? AND fact LIKE '专业：%'", Integer.class, id)).isZero();
        assertThat(notes.get("usr_other", otherNote).content()).isEqualTo(draft.content());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_note_imports WHERE user_id=?", Integer.class, id)).isZero();
        assertThat(notes.list(id,1,20).total()).isEqualTo(3);
        for (var entry : Map.of("resumes", 2, "resume_versions", 2, "files", 2, "applications", 6,
                "interview_sessions", 2, "career_profiles", 1, "growth_task_completions", 3).entrySet()) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + entry.getKey() + " WHERE user_id=?", Integer.class, id))
                    .as(entry.getKey()).isEqualTo(entry.getValue());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_answers", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM occupations", Integer.class)).isEqualTo(occupations);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE id='job_real'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT note FROM applications WHERE id='app_other'", String.class)).isEqualTo("保留其他用户");
        assertThat(jdbc.queryForObject("SELECT target FROM career_profiles WHERE user_id='usr_other'", String.class)).isEqualTo("其他用户目标");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversations WHERE user_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM career_assessments WHERE user_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE user_id=?", Integer.class, id)).isZero();
        jdbc.update("UPDATE applications SET note='评委试用改动' WHERE user_id=?", id);
        bootstrap.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM applications WHERE user_id=? AND note='评委试用改动'", Integer.class, id)).isEqualTo(6);
    }

    @Test void requiresActiveStaffForReset() throws Exception {
        assertThat(request("POST", "/admin/demo/reset", null, null).statusCode()).isEqualTo(401);
        String demo = json.readTree(request("POST", "/auth/demo-login", null, null).body()).path("data").path("accessToken").asText();
        assertThat(request("POST", "/admin/demo/reset", demo, null).statusCode()).isEqualTo(403);
        jdbc.update("INSERT INTO users(id,email,password_hash,roles,nickname) VALUES ('usr_staff','staff@offerpilot.local',?,'{STAFF}','客服')",
                passwords.encode("isolated-staff-test-password"));
        var login = request("POST", "/auth/staff/login", null, Map.of("email", "staff@offerpilot.local", "password", "isolated-staff-test-password"));
        String staff = json.readTree(login.body()).path("data").path("accessToken").asText();
        var reset = request("POST", "/admin/demo/reset", staff, null);
        assertThat(reset.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reset.body()).path("data").path("resumeCount").asInt()).isEqualTo(2);
        jdbc.update("UPDATE users SET roles='{USER}' WHERE id='usr_staff'");
        assertThat(request("POST", "/admin/demo/reset", staff, null).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", owner());
        assertThatThrownBy(() -> accounts.login(logins)).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        assertThat(request("POST", "/auth/demo-login", null, null).statusCode()).isEqualTo(200);
    }

    @Test void storageFailureRollsBackBusinessReplacementAndSuccessfulResetCleansOldUploads() {
        String id = owner();
        jdbc.update("UPDATE career_profiles SET target='保留试用改动' WHERE user_id=?", id);
        var memory = (MemoryDemoStorage) storage;
        String key = "knowledge/" + id + "/extra-upload";
        memory.put(key, new byte[]{1, 2, 3}, "application/pdf");
        jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES ('file_extra',?,'KB_DOCUMENT',?,?,'extra.pdf','application/pdf',3,'fixture')", id, storage.bucket(), key);
        String note = notes.create(id, new com.offerpilot.knowledge.application.KnowledgeNoteService.Draft("上传资料", "保留上传原文", "技术积累")).id();
        jdbc.update("UPDATE knowledge_notes SET source_file_id='file_extra',source_filename='extra.pdf' WHERE id=?", note);
        memory.failWrites = true;
        try { assertThatThrownBy(resets::reset).isInstanceOf(com.offerpilot.common.error.BusinessException.class); }
        finally { memory.failWrites = false; }
        assertThat(jdbc.queryForObject("SELECT target FROM career_profiles WHERE user_id=?", String.class, id)).isEqualTo("保留试用改动");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resumes WHERE user_id=?", Integer.class, id)).isEqualTo(2);
        assertThat(memory.get(storage.bucket(), key)).containsExactly(1, 2, 3);
        resets.reset();
        assertThat(memory.get(storage.bucket(), key)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_notes WHERE id=?", Integer.class, note)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE purpose='KB_DOCUMENT' AND user_id=?", Integer.class, id)).isZero();
    }

    @Test void concurrentResetsAndScheduledResetKeepOneCompleteJourney() {
        var first = java.util.concurrent.CompletableFuture.supplyAsync(resets::reset);
        var second = java.util.concurrent.CompletableFuture.supplyAsync(resets::reset);
        assertThat(first.join().resumeCount()).isEqualTo(2);
        assertThat(second.join().applicationCount()).isEqualTo(6);
        jdbc.update("UPDATE career_profiles SET target='试用修改' WHERE user_id=?", owner());
        new DemoJourneyBootstrap(accounts, resets, new OfferPilotProperties(null,
                new OfferPilotProperties.Demo(true, "demo@offerpilot.local", "isolated-demo-test-password", "-", false, java.time.Duration.ofHours(24), 200000, 30, 100),
                null,null,null,null,null,null)).scheduledReset();
        assertThat(jdbc.queryForObject("SELECT target FROM career_profiles WHERE user_id=?", String.class, owner())).isEqualTo("后端开发工程师");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resumes WHERE user_id=?", Integer.class, owner())).isEqualTo(2);
    }

    @Test void neverIssuesStaffJwtForAnIncorrectlyFlaggedDemoUser() throws Exception {
        jdbc.update("UPDATE users SET roles='{USER,STAFF}' WHERE id=?", owner());
        assertThatThrownBy(() -> accounts.login(logins)).isInstanceOfSatisfying(com.offerpilot.common.error.BusinessException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(com.offerpilot.common.error.ErrorCode.DEMO_NOT_ALLOWED));
        var response = request("POST", "/auth/demo-login", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("data").path("user").path("roles").toString()).isEqualTo("[\"USER\"]");
    }

    @Test void rotatesConfiguredPasswordAndEmailButNeverHijacksRealAccount() {
        String id = owner();
        var rotated = accountConfig(true, "new-demo@offerpilot.local", "isolated-rotated-demo-password");
        assertThat(rotated.ensure()).isEqualTo(id);
        assertThat(passwords.matches("isolated-rotated-demo-password", jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, id))).isTrue();
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, id)).isEqualTo("new-demo@offerpilot.local");
        jdbc.update("INSERT INTO users(id,email,password_hash,nickname) VALUES ('usr_real','real@example.edu.cn','unchanged','真实账号夹具')");
        assertThat(accountConfig(true, "real@example.edu.cn", "isolated-test-password").ensure()).isNull();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id='usr_real'", String.class)).isEqualTo("unchanged");
        assertThat(jdbc.queryForObject("SELECT is_demo FROM users WHERE id='usr_real'", Boolean.class)).isFalse();
        assertThat(accountConfig(false, "demo@offerpilot.local", "isolated-test-password").ensure()).isNull();
        assertThat(accountConfig(true, "demo@offerpilot.local", "").ensure()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE is_demo=true", Integer.class)).isEqualTo(1);
    }

    @Test void sandboxLoginsIsolateJourneyQuotaAndExpiryCleanup() throws Exception {
        var first = java.util.concurrent.CompletableFuture.supplyAsync(sandboxes::login);
        var second = java.util.concurrent.CompletableFuture.supplyAsync(sandboxes::login);
        var a = first.join(); var b = second.join();
        String owner = a.user().id();
        assertThat(owner).isNotEqualTo(b.user().id());
        assertThat(a.user().demoExpiresAt()).isAfter(java.time.Instant.now().plusSeconds(23*3600));
        assertThatThrownBy(() -> logins.login(a.user().email(), "isolated-demo-test-password"))
                .isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        assertThat(request("GET", "/staff/users", a.accessToken(), null).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/admin/demo/reset", a.accessToken(), null).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/auth/demo-login", null, null).statusCode()).isEqualTo(429);
        var aResumes = json.readTree(request("GET", "/resumes", a.accessToken(), null).body()).path("data").path("items");
        var bResumes = json.readTree(request("GET", "/resumes", b.accessToken(), null).body()).path("data").path("items");
        assertThat(aResumes).hasSize(2); assertThat(bResumes).hasSize(2);
        assertThat(request("GET", "/resumes/"+aResumes.get(0).path("id").asText(), b.accessToken(), null).statusCode()).isEqualTo(404);
        String app = "app_demo_"+owner+"_1";
        assertThat(request("PATCH", "/applications/"+app, b.accessToken(), Map.of("note", "foreign" )).statusCode()).isEqualTo(404);
        assertThat(request("PATCH", "/applications/"+app, a.accessToken(), Map.of("note", "visitor A" )).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT note FROM applications WHERE user_id=?", String.class,b.user().id())).doesNotContain("visitor A");
        assertThat(request("GET", "/interviews/int_demo_"+owner+"_1", b.accessToken(), null).statusCode()).isEqualTo(404);
        var admission = limiter.check(owner, com.offerpilot.llm.ModelTier.FLASH, true);
        limiter.record(admission, 150000);
        assertThatThrownBy(() -> limiter.check(owner, com.offerpilot.llm.ModelTier.FLASH, false))
                .isInstanceOfSatisfying(com.offerpilot.common.error.BusinessException.class,
                    ex -> assertThat(ex.errorCode()).isEqualTo(com.offerpilot.common.error.ErrorCode.DAILY_QUOTA_EXCEEDED));
        assertThat(limiter.check(b.user().id(), com.offerpilot.llm.ModelTier.FLASH, false)).isNotNull();
        assertThat(json.readTree(request("GET", "/demo/sandbox", a.accessToken(), null).body()).path("data").path("remainingTokens").asLong()).isZero();
        jdbc.update("UPDATE user_preferences SET daily_token_limit=999999 WHERE user_id=?",owner);
        assertThat(quotas.policy(owner).dailyTokenLimit()).isEqualTo(150000);
        var turn = conversations.begin(owner, null, "fictional", com.offerpilot.agent.domain.AgentMode.AUTO, "FLASH");
        var ticket = approvals.request(owner, turn.runId(), "updateApplicationStatus", Map.of("applicationId",app,"note","fictional"));
        String approvalPath = "/agent/runs/"+turn.runId()+"/approvals/"+ticket.id();
        assertThat(request("POST",approvalPath,b.accessToken(),Map.of("decision","APPROVE")).statusCode()).isEqualTo(404);
        assertThat(request("POST",approvalPath,a.accessToken(),Map.of("decision","REJECT")).statusCode()).isEqualTo(200);
        jdbc.update("INSERT INTO approvals(id,run_id,user_id,tool_name,args,status,expires_at) VALUES ('sandbox-approval',?,?,'fixture','{}','PENDING',now()+interval '1 hour')",turn.runId(),owner);
        var objects = jdbc.queryForList("SELECT object_key FROM files WHERE user_id=?",String.class,owner);
        var indirect = jdbc.queryForList("SELECT session_id FROM interview_answers WHERE session_id IN (SELECT id FROM interview_sessions WHERE user_id=?)",String.class,owner);
        // Expiry is checked on every authenticated request, before the deletion poll, even if staff suspended it.
        jdbc.update("UPDATE users SET demo_expires_at=now()-interval '1 second' WHERE id=?",owner);
        assertThat(request("GET", "/resumes", a.accessToken(), null).statusCode()).isEqualTo(401);
        assertThatThrownBy(() -> quotas.policy(owner)).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?",owner);
        sandboxes.cleanupExpired();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class,owner)).isZero();
        for (String table : jdbc.queryForList("SELECT table_name FROM information_schema.columns WHERE table_schema='public' AND column_name='user_id'",String.class))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE user_id=?",Integer.class,owner)).as(table).isZero();
        for (String id : indirect) assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_answers WHERE session_id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE id=?",Integer.class,turn.runId())).isZero();
        for (String key : objects) assertThat(storage.get(storage.bucket(),key)).isNull();
        assertThat(usage.tokens(owner,admission.day())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_data_cleanup",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resumes WHERE user_id=?",Integer.class,b.user().id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE site='demo'",Integer.class)).isEqualTo(10);
        var c = sandboxes.login();
        assertThat(request("DELETE", "/users/me/data", c.accessToken(), Map.of("confirm","DELETE")).statusCode()).isEqualTo(200);
        // Deleted/failed sandboxes do not refund daily admission, even after active capacity opens.
        assertThat(request("POST", "/auth/demo-login", null, null).statusCode()).isEqualTo(429);
    }

    @Test void sandboxSeedFailureReclaimsPartialObjectsAndKeepsAdmission() {
        var memory = (MemoryDemoStorage) storage;
        memory.beforePut = () -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM account_data_cleanup WHERE kind='OBJECT' AND next_attempt_at>now()",Integer.class)).isEqualTo(2);
        // Fail after uploads at the career-profile insert to verify rollback object cleanup.
        jdbc.execute("ALTER TABLE career_profiles ADD CONSTRAINT sandbox_seed_failure CHECK (user_id NOT LIKE 'usr_%') NOT VALID");
        try { assertThatThrownBy(sandboxes::login).isInstanceOf(RuntimeException.class); }
        finally {
            jdbc.execute("ALTER TABLE career_profiles DROP CONSTRAINT sandbox_seed_failure");
            memory.beforePut = () -> {};
        }
        cleanup.retryPending();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE demo_expires_at IS NOT NULL",Integer.class)).isZero();
        assertThat(memory.objects.keySet()).allMatch(key -> key.contains(owner()));
        assertThat(jdbc.queryForObject("SELECT created_count FROM demo_sandbox_admissions",Integer.class)).isEqualTo(1);
        String linked = jdbc.queryForObject("SELECT object_key FROM files WHERE user_id=? LIMIT 1",String.class,owner());
        jdbc.update("INSERT INTO account_data_cleanup(id,kind,bucket,object_key) VALUES ('crash-after-commit','OBJECT',?,?)",storage.bucket(),linked);
        cleanup.retryPending();
        assertThat(storage.get(storage.bucket(),linked)).isNotEmpty();
    }

    private DemoAccountBootstrap accountConfig(boolean enabled, String email, String password) {
        return new DemoAccountBootstrap(new OfferPilotProperties(null,
                new OfferPilotProperties.Demo(enabled, email, password, "-"), null, null, null, null, null, null), jdbc, passwords, transactions);
    }

    private HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return HttpClient.newHttpClient().send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void demoTargetResolvesToCareerLibraryAndProducesCoachCards() {
        var target = jdbc.queryForObject("SELECT target FROM career_profiles WHERE user_id=?", String.class, owner());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM occupations WHERE name=?", Integer.class, target)).isEqualTo(1);
        var coach = new com.offerpilot.career.application.DefaultCoachPlanForChat(
            new com.offerpilot.career.application.CareerProfileService(jdbc, json),
            new com.offerpilot.career.application.OccupationRecommender(jdbc), jdbc, json,
            new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(com.offerpilot.career.application.CoachResumeSkills.class));
        var result = coach.plan(owner(), null, null, "demo-target-test");
        assertThat(result.skillGap()).isNotNull();
        assertThat(result.roadmap().weeks()).hasSize(4);
    }
}
