package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.auth.application.JwtTokenService;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.privacy.AccountDataCleanup;
import com.offerpilot.resume.application.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.ai.model.chat=none", "spring.ai.model.embedding=none", "spring.ai.mcp.server.enabled=false",
    "offerpilot.resume.worker-enabled=false", "offerpilot.privacy.cleanup-delay-ms=3600000"
})
@ActiveProfiles("test")
@Testcontainers
class AccountDataDeletionIntegrationTest {
    @Container static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container static GenericContainer<?> s3 = new GenericContainer<>(DockerImageName.parse("adobe/s3mock@sha256:7a37f0d796e81a28b970c892dcae532797014616b3312b467af8f0274ebf0c26"))
        .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", "deletion-test").withExposedPorts(9090)
        .waitingFor(Wait.forHttp("/").forPort(9090).forStatusCode(200));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", database::getJdbcUrl); r.add("spring.datasource.username", database::getUsername); r.add("spring.datasource.password", database::getPassword);
        r.add("offerpilot.storage.endpoint", () -> "http://" + s3.getHost() + ":" + s3.getMappedPort(9090));
        r.add("offerpilot.storage.access-key", () -> "deletion-test"); r.add("offerpilot.storage.secret-key", () -> "fixture-only-password");
        r.add("offerpilot.storage.bucket", () -> "deletion-test");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenService tokens;
    @Autowired GuestIdentityService guests;
    @Autowired ResumeService resumes;
    @Autowired ConversationService conversations;
    @Autowired AccountDataCleanup cleanup;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @SpyBean ResumeObjectStore storage;
    @LocalServerPort int port;
    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> DIRECT = Set.of("github_evidence","job_daily_recommendations","generation_tasks","user_memories","user_preferences","refresh_tokens","user_integrations","user_mcp_servers","guest_sessions","conversations","messages","agent_runs","approvals","files","resumes","resume_versions","resume_model_preferences","resume_diagnoses","resume_patch_batches","resume_optimizations","job_matches","applications","interview_sessions","interview_learning_plans","star_stories","career_profiles","career_recommendation_revisions","career_assessments","growth_task_completions","knowledge_notes","knowledge_note_imports","jobs");
    static final Map<String,String> INDIRECT = Map.of("application_events","application_id IN (SELECT id FROM applications WHERE user_id=?)", "agent_steps","run_id IN (SELECT id FROM agent_runs WHERE user_id=?)", "tool_calls","run_id IN (SELECT id FROM agent_runs WHERE user_id=?)", "agent_events","run_id IN (SELECT id FROM agent_runs WHERE user_id=?)", "interview_answers","session_id IN (SELECT id FROM interview_sessions WHERE user_id=?)");
    HttpResponse<String> request(String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(30));
        if (token!=null) b.header("Authorization", "Bearer "+token);
        if (path.equals("/users/me/data")) b.header("Content-Type","application/json").method("DELETE",body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        else b.GET();
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    String owner() { return guests.require("Bearer "+guests.create().accessToken()); }
    List<String> rows(String table, String owner) {
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM "+table+" t WHERE "+INDIRECT.getOrDefault(table,"user_id=?")+" ORDER BY row_to_json(t)::text",String.class,owner);
    }
    void seed(String o) throws Exception {
        jdbc.update("INSERT INTO user_preferences(user_id) VALUES (?)",o);
        jdbc.update("INSERT INTO refresh_tokens(id,user_id,token_hash,expires_at) VALUES (?,?,?,now()+interval '1 day')",o+"rt",o,o+"hash");
        jdbc.update("INSERT INTO user_integrations(id,user_id,provider,secret_enc) VALUES (?,?,'GITHUB',decode('00','hex'))",o+"integration",o);
        jdbc.update("INSERT INTO user_mcp_servers(user_id,provider) VALUES (?,'jobs')",o);
        jdbc.update("INSERT INTO github_evidence(user_id,username,token_ciphertext,revision) VALUES (?,'fixture','v1:fixture:encrypted','fixture')",o);
        for (String purpose: List.of("RESUME","EXPORT","KB_DOCUMENT")) {
            String key="resumes/"+o+"/"+purpose;
            storage.put(key,"fictional resume".getBytes(),"text/plain");
            jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES (?,?,?,?,?,'fixture.txt','text/plain',16,'fixture')",o+purpose,o,purpose,storage.bucket(),key);
        }
        jdbc.update("INSERT INTO resumes(id,user_id,title,source_file_id,parse_status) VALUES (?,?,'fixture',?,'READY')",o+"resume",o,o+"RESUME");
        jdbc.update("INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text) VALUES (?,?,?,1,'UPLOAD','{}','private')",o+"v",o+"resume",o);
        jdbc.update("INSERT INTO resume_versions(id,resume_id,user_id,version_no,parent_version_id,source,structured,plain_text) VALUES (?,?,?,2,?,'OPTIMIZED','{}','private')",o+"v2",o+"resume",o,o+"v");
        jdbc.update("UPDATE resumes SET current_version_id=? WHERE id=?",o+"v2",o+"resume");
        jdbc.update("INSERT INTO resume_model_preferences(user_id,text_tier,vision_tier) VALUES (?,'FLASH','FLASH')",o);
        jdbc.update("INSERT INTO resume_diagnoses(id,user_id,resume_id,version_id,request_id,status,model_tier) VALUES (?,?,?,?,'diagnosis','RUNNING','FLASH')",o+"diag",o,o+"resume",o+"v");
        jdbc.update("INSERT INTO resume_patch_batches(id,user_id,resume_id,resume_version_id,patches,applied_version_id,decision) VALUES (?,?,?,?,'[{}]',?,'{}')",o+"patch",o,o+"resume",o+"v",o+"v2");
        jdbc.update("INSERT INTO resume_optimizations(id,user_id,resume_id,version_id,request_id,status,model_tier,job_description) VALUES (?,?,?,?,'optimization','RUNNING','FLASH','private')",o+"opt",o,o+"resume",o+"v");
        jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload) VALUES (?,'deletion',?,'campus','{}')",o+"job",o);
        jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload,user_id) VALUES (?,'imported',?,'campus','{}',?)",o+"imported",o,o);
        jdbc.update("INSERT INTO applications(id,user_id,job_id,note) VALUES (?,?,?,'private import')",o+"app-imported",o,o+"imported");
        jdbc.update("UPDATE resume_optimizations SET job_id=? WHERE id=?",o+"job",o+"opt");
        jdbc.update("INSERT INTO job_daily_recommendations(user_id,day,context_hash,snapshot) VALUES (?,current_date,'fixture','{\"items\":[]}'::jsonb)",o);
        jdbc.update("INSERT INTO job_matches(id,user_id,job_id,resume_version_id,request_id,status) VALUES (?,?,?,?,'match','RUNNING')",o+"match",o,o+"job",o+"v");
        jdbc.update("INSERT INTO applications(id,user_id,job_id,note) VALUES (?,?,?,'private')",o+"app",o,o+"job");
        jdbc.update("INSERT INTO application_events(id,application_id,kind,to_status,detail,actor) VALUES (?,?,'CREATED','TODO','private','USER')",o+"event",o+"app");
        jdbc.update("INSERT INTO interview_sessions(id,user_id,role,kind,job_id) VALUES (?,?,'开发','项目与经历',?)",o+"interview",o,o+"job");
        jdbc.update("INSERT INTO interview_learning_plans(id,user_id,report_id,roadmap,start_date) VALUES (?,?,?,'{\"weeks\":[],\"milestones\":[]}',current_date)",o+"plan",o,o+"interview");
        jdbc.update("INSERT INTO interview_answers(session_id,question_index,question_text,answer_text) VALUES (?,0,'fictional','private')",o+"interview");
        jdbc.update("INSERT INTO star_stories(id,user_id,title,body,role,source_session_id,source_question_index,star_count,situation,task,action,result,normalized_body,tsv) VALUES (?,?,'fixture','private STAR body','开发',?,0,4,true,true,true,true,'private star body','')",o+"star",o,o+"interview");
        jdbc.update("INSERT INTO career_profiles(user_id,skills,major,courses,values_text) VALUES (?,'private','private major','private courses','private values')",o);
        jdbc.update("INSERT INTO career_recommendation_revisions(id,user_id,snapshot) VALUES (?,?,'{}')",o+"revision",o);
        jdbc.update("INSERT INTO career_recommendation_revisions(id,user_id,parent_id,snapshot) VALUES (?,?,?,'{}')",o+"revision-child",o,o+"revision");
        jdbc.update("INSERT INTO growth_task_completions(user_id,task_title) VALUES (?,'fixture')",o);
        jdbc.update("INSERT INTO generation_tasks(id,user_id,kind,entity_id,request_id,request_hash,status,stage,title,href,result) VALUES (?,?,'JOB_IMPORT','current',?,'fixture','SUCCEEDED','saved','private','/jobs','{\"private\":true}'::jsonb)","gen_"+o,o,"request_"+o);
        jdbc.update("INSERT INTO career_assessments(user_id,answers) VALUES (?,'[\"private\"]'::jsonb)",o);
        jdbc.update("INSERT INTO knowledge_notes(id,user_id,title,content,category,tsv,embedding) VALUES (?,?,'fixture','private','技术积累','',?::vector)",o+"note",o,"["+String.join(",",Collections.nCopies(1024,"0.1"))+"]");
        jdbc.update("UPDATE knowledge_notes SET source_file_id=?,source_filename='资料.md',index_status='READY' WHERE id=?", o+"KB_DOCUMENT", o+"note");
        jdbc.update("INSERT INTO knowledge_note_imports(user_id,source_id,fingerprint,note_id) VALUES (?,'fixture',?,?)",o,"0".repeat(64),o+"note");
        jdbc.update("INSERT INTO user_memories(id,user_id,kind,fact,normalized_fact,source,tsv) VALUES (?, ?, 'GOAL', '虚构目标', '虚构目标', 'MANUAL', '')",o+"memory",o);
        var turn = conversations.begin(o,null,"private",AgentMode.AUTO,"fixture");
        jdbc.update("INSERT INTO agent_steps(id,run_id,step_key,title,agent,status,ordinal) VALUES (?,?,'fixture','private','fixture','RUNNING',0)",o+"step",turn.runId());
        jdbc.update("INSERT INTO tool_calls(id,run_id,step_id,name,source,access,args) VALUES (?,?,?,'fixture','fixture','WRITE','{}')",o+"tool",turn.runId(),o+"step");
        jdbc.update("INSERT INTO approvals(id,run_id,user_id,tool_name,args,status,expires_at) VALUES (?,?,?,'fixture','{}','PENDING',now()+interval '1 hour')",o+"approval",turn.runId(),o);
        jdbc.update("INSERT INTO agent_events(run_id,seq,type,payload) VALUES (?,1,'fixture','{}')",turn.runId());
    }
    @Test void deletesEveryOwnedTableAndObjectsWithoutTouchingAnotherIdentity() throws Exception {
        String a=owner(),b=owner(); seed(a);seed(b);
        Set<String> tables=new HashSet<>(DIRECT); tables.addAll(INDIRECT.keySet());
        var before = new HashMap<String,List<String>>();
        var childIds = new HashMap<String,List<String>>();
        for (String table:tables) { assertThat(rows(table,a)).as(table+" seeded").isNotEmpty(); before.put(table,rows(table,b)); }
        for(String table:INDIRECT.keySet()) {
            String column=table.equals("interview_answers")?"session_id":table.equals("agent_events")?"run_id":"id";
            childIds.put(table,jdbc.queryForList("SELECT "+column+" FROM "+table+" WHERE "+INDIRECT.get(table),String.class,a));
        }
        String jwt=tokens.issue(a,List.of("USER"),"fixture");
        assertThat(request("/users/me/data",jwt,"{\"confirm\":\"DELETE\"}").statusCode()).isEqualTo(200);
        for (String table:tables) { assertThat(rows(table,a)).as(table+" A deleted").isEmpty(); assertThat(rows(table,b)).as(table+" B preserved").isEqualTo(before.get(table)); }
        for (String table:INDIRECT.keySet()) {
            String column=table.equals("interview_answers")?"session_id":table.equals("agent_events")?"run_id":"id";
            for(String id:childIds.get(table)) assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+column+"=?",Integer.class,id)).isZero();
        }
        for(String purpose:List.of("RESUME","EXPORT","KB_DOCUMENT")) {
            assertThatThrownBy(() -> storage.get(storage.bucket(),"resumes/"+a+"/"+purpose)).isInstanceOf(RuntimeException.class);
            assertThat(storage.get(storage.bucket(),"resumes/"+b+"/"+purpose)).isEqualTo("fictional resume".getBytes());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?",Integer.class,a)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_data_cleanup",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE id=?",Integer.class,a+"job")).isEqualTo(1);
        assertThat(request("/auth/verify",jwt,null).statusCode()).isEqualTo(401);
        assertThat(request("/conversations",jwt,null).statusCode()).isEqualTo(401);
    }
    @Test void validationAuthenticationAndProtectedAccounts() throws Exception {
        String guest=guests.create().accessToken();
        for (String body:Arrays.asList(null,"{}","{\"confirm\":\"delete\"}","{\"confirm\":true}","{\"confirm\":\"DELETE\",\"id\":\"someone\"}")) {
            var result=request("/users/me/data",guest,body);assertThat(result.statusCode()).isEqualTo(400);assertThat(result.body()).contains("10001");
        }
        assertThat(request("/users/me/data",null,"{\"confirm\":\"DELETE\"}").statusCode()).isEqualTo(401);
        for(boolean demo:List.of(true,false)) {
            String o=owner(); jdbc.update("UPDATE users SET is_demo=?,roles=?::text[] WHERE id=?",demo,demo?"{USER}":"{USER,STAFF}",o);
            var result=request("/users/me/data",tokens.issue(o,demo?List.of("USER"):List.of("USER","STAFF"),"fixture"),"{\"confirm\":\"DELETE\"}");
            assertThat(result.statusCode()).isEqualTo(403);assertThat(result.body()).contains(demo?"20007":"20004");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?",Integer.class,o)).isEqualTo(1);
        }
    }
    @Test void guestDeletionIgnoresLateParseAndSseWritesAndRetriesStorageFailures() throws Exception {
        String guest=guests.create().accessToken(),o=guests.require("Bearer "+guest);
        var resume=resumes.upload(o,"fictional.txt",null,"fictional".getBytes()); var job=resumes.claim(); assertThat(job.id()).isEqualTo(resume.id());
        var turn=conversations.begin(o,null,"private",AgentMode.AUTO,"fixture");
        doThrow(new IllegalStateException("fixture storage outage")).when(storage).remove(job.bucket(),job.key());
        try {
            assertThat(request("/users/me/data",guest,"{\"confirm\":\"DELETE\"}").statusCode()).isEqualTo(200);
            assertThat(request("/conversations",guest,null).statusCode()).isEqualTo(401);
            resumes.complete(job,JSON.readTree(ResumeStructure.SCHEMA),JSON.createObjectNode());resumes.fail(job,"late");
            assertThat(conversations.checkpoint(turn,"late private")).isFalse();
            assertThat(conversations.finish(turn,"late private","COMPLETED",null,null)).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM resume_versions WHERE user_id=?",Integer.class,o)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE user_id=?",Integer.class,o)).isZero();
            assertThat(jdbc.queryForObject("SELECT attempts FROM account_data_cleanup WHERE object_key=?",Integer.class,job.key())).isEqualTo(1);
            assertThat(storage.get(job.bucket(),job.key())).isNotEmpty();
        } finally { doCallRealMethod().when(storage).remove(job.bucket(),job.key()); }
        jdbc.queryForList("SELECT id FROM account_data_cleanup WHERE object_key=?",String.class,job.key()).forEach(cleanup::clean);
        assertThatThrownBy(() -> storage.get(job.bucket(),job.key())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_data_cleanup WHERE object_key=?",Integer.class,job.key())).isZero();
    }
    @Test void deletionWaitsForAnUploadingObjectAndThenIncludesItInCleanup() throws Exception {
        String guest=guests.create().accessToken(),o=guests.require("Bearer "+guest);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        var key=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(call -> {
            key.set(call.getArgument(0));entered.countDown();
            if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("fixture upload release timeout");
            return call.callRealMethod();
        }).when(storage).put(startsWith("resumes/"+o+"/"),any(byte[].class),anyString());
        try {
            var upload=executor.submit(() -> {
                try { resumes.upload(o,"racing.txt",null,"fictional".getBytes()); }
                catch(com.offerpilot.common.error.BusinessException e) {
                    // Deletion can win the subsequent response read after upload transaction commits.
                    assertThat(e.errorCode()).isEqualTo(com.offerpilot.common.error.ErrorCode.RESUME_NOT_FOUND);
                }
            });
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var deletion=executor.submit(() -> request("/users/me/data",guest,"{\"confirm\":\"DELETE\"}"));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND query LIKE 'SELECT is_demo,%' AND wait_event_type='Lock'",Integer.class)>0);
            release.countDown();upload.get(15,TimeUnit.SECONDS);
            assertThat(deletion.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE user_id=?",Integer.class,o)).isZero();
            assertThatThrownBy(() -> storage.get(storage.bucket(),key.get())).isInstanceOf(RuntimeException.class);
            // A request that passed auth before deletion cannot upload another object afterward.
            assertThatThrownBy(() -> resumes.upload(o,"late.txt",null,"fictional".getBytes())).isInstanceOf(RuntimeException.class);
        } finally { release.countDown();executor.shutdownNow();doCallRealMethod().when(storage).put(startsWith("resumes/"+o+"/"),any(byte[].class),anyString()); }
    }
    @Test void deletionCompletesWhenAParserAlreadyHoldsAResumeLock() throws Exception {
        String guest=guests.create().accessToken(),o=guests.require("Bearer "+guest);
        var resume=resumes.upload(o,"parsing.txt",null,"fictional".getBytes());
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var parsing=executor.submit(() -> {
                try { tx.executeWithoutResult(status -> {
                    jdbc.queryForList("SELECT id FROM resumes WHERE id=? FOR UPDATE",resume.id());locked.countDown();
                    try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("fixture parser release timeout"); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
                    // ResumeService.complete takes this row lock before inserting the FK-backed version.
                    jdbc.update("INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text) VALUES (?,?,?,1,'UPLOAD','{}','private')",o+"racing-version",resume.id(),o);
                }); } catch(org.springframework.dao.DataAccessException ignored) {
                    // PostgreSQL may choose the late parser as deadlock victim; rollback is safe.
                }
            });
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var deletion=executor.submit(() -> request("/users/me/data",guest,"{\"confirm\":\"DELETE\"}"));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND query LIKE 'DELETE FROM users WHERE id=%' AND wait_event_type='Lock'",Integer.class)>0);
            release.countDown();parsing.get(15,TimeUnit.SECONDS);
            var result=deletion.get(15,TimeUnit.SECONDS);
            assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?",Integer.class,o)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM resume_versions WHERE user_id=?",Integer.class,o)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM account_data_cleanup WHERE object_key=? OR object_key LIKE ?",Integer.class,o,"resumes/"+o+"/%")).isZero();
        } finally { release.countDown();executor.shutdownNow(); }
    }
    @Test void inventoryRequiresCoverageForEveryOwnerColumnAndCascadeDescendant() {
        var owned=jdbc.queryForList("SELECT DISTINCT table_name FROM information_schema.columns WHERE table_schema='public' AND column_name IN ('user_id','owner_id')",String.class);
        assertThat(new HashSet<>(owned)).isEqualTo(DIRECT);
        var directFks=jdbc.queryForList("SELECT DISTINCT c.conrelid::regclass::text FROM pg_constraint c WHERE c.contype='f' AND c.confrelid='users'::regclass",String.class);
        assertThat(new HashSet<>(directFks)).isEqualTo(DIRECT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype='f' AND confrelid='users'::regclass AND confdeltype<>'c'",Integer.class)).isZero();
        var descendants=jdbc.queryForList("""
            WITH RECURSIVE owned(oid) AS (
              SELECT 'users'::regclass::oid
              UNION SELECT c.conrelid FROM pg_constraint c JOIN owned o ON c.confrelid=o.oid WHERE c.contype='f' AND c.confdeltype='c'
            ) SELECT relname FROM pg_class WHERE oid IN (SELECT oid FROM owned) AND relname<>'users'
            """,String.class);
        var expected=new HashSet<>(DIRECT);expected.addAll(INDIRECT.keySet()); assertThat(new HashSet<>(descendants)).isEqualTo(expected);
    }
}
