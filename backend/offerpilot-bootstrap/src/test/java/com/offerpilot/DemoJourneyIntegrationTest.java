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
        public String bucket() { return "isolated-demo-fixtures"; }
        public void put(String key, byte[] bytes, String mime) {
            if (failWrites) throw new com.offerpilot.common.error.BusinessException(com.offerpilot.common.error.ErrorCode.STORAGE_UNAVAILABLE);
            objects.put(key, bytes);
        }
        public byte[] get(String bucket, String key) { return objects.get(key); }
        public void remove(String bucket, String key) { objects.remove(key); }
    }

    @BeforeEach void freshJourney() {
        jdbc.execute("TRUNCATE users CASCADE");
        accounts.run(new DefaultApplicationArguments());
        bootstrap.run(new DefaultApplicationArguments());
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
        for (var file : jdbc.queryForList("SELECT bucket,object_key,mime,size FROM files WHERE user_id=?", owner())) {
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
        var draft = new com.offerpilot.knowledge.application.KnowledgeNoteService.Draft("其他用户笔记", "保留其他用户的原文", "技术积累");
        String otherNote = notes.create("usr_other", draft).id();
        notes.importNotes(owner(), java.util.List.of(new com.offerpilot.knowledge.application.KnowledgeNoteService.ImportEntry("old-demo", draft)));
        int occupations = jdbc.queryForObject("SELECT count(*) FROM occupations", Integer.class);
        String id = owner();
        var first = resets.reset();
        var second = resets.reset();
        assertThat(first.resumeCount()).isEqualTo(2);
        assertThat(second.applicationCount()).isEqualTo(6);
        assertThat(second.jobCount()).isEqualTo(10);
        assertThat(second.knowledgeNoteCount()).isEqualTo(3);
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
        assertThat(request("POST", "/auth/demo-login", null, null).statusCode()).isEqualTo(403);
    }

    @Test void storageFailureRollsBackBusinessReplacementAndSuccessfulResetCleansOldUploads() {
        String id = owner();
        jdbc.update("UPDATE career_profiles SET target='保留试用改动' WHERE user_id=?", id);
        var memory = (MemoryDemoStorage) storage;
        String key = "resumes/" + id + "/extra-upload";
        memory.put(key, new byte[]{1, 2, 3}, "application/pdf");
        jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES ('file_extra',?,'RESUME',?,?,'extra.pdf','application/pdf',3,'fixture')", id, storage.bucket(), key);
        memory.failWrites = true;
        try { assertThatThrownBy(resets::reset).isInstanceOf(com.offerpilot.common.error.BusinessException.class); }
        finally { memory.failWrites = false; }
        assertThat(jdbc.queryForObject("SELECT target FROM career_profiles WHERE user_id=?", String.class, id)).isEqualTo("保留试用改动");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resumes WHERE user_id=?", Integer.class, id)).isEqualTo(2);
        assertThat(memory.get(storage.bucket(), key)).containsExactly(1, 2, 3);
        resets.reset();
        assertThat(memory.get(storage.bucket(), key)).isNull();
    }

    @Test void concurrentResetsAndScheduledResetKeepOneCompleteJourney() {
        var first = java.util.concurrent.CompletableFuture.supplyAsync(resets::reset);
        var second = java.util.concurrent.CompletableFuture.supplyAsync(resets::reset);
        assertThat(first.join().resumeCount()).isEqualTo(2);
        assertThat(second.join().applicationCount()).isEqualTo(6);
        jdbc.update("UPDATE career_profiles SET target='试用修改' WHERE user_id=?", owner());
        bootstrap.scheduledReset();
        assertThat(jdbc.queryForObject("SELECT target FROM career_profiles WHERE user_id=?", String.class, owner())).isEqualTo("后端开发工程师");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resumes WHERE user_id=?", Integer.class, owner())).isEqualTo(2);
    }

    @Test void neverIssuesStaffJwtForAnIncorrectlyFlaggedDemoUser() throws Exception {
        jdbc.update("UPDATE users SET roles='{USER,STAFF}' WHERE id=?", owner());
        var response = request("POST", "/auth/demo-login", null, null);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(20007);
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
