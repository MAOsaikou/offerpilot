package com.offerpilot;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.board.application.DeadlineReminders;
import com.offerpilot.career.application.*;
import com.offerpilot.notification.application.Notifications;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import java.net.*;
import java.net.http.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@TestPropertySource(properties={"offerpilot.career.target-initial-delay-ms=3600000","offerpilot.career.profile-initial-delay-ms=3600000","offerpilot.notifications.new-job-initial-delay-ms=3600000","offerpilot.notifications.deadline-cron=-"})
class NotificationCareerHttpIntegrationTest extends SharedHttpIntegrationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    @LocalServerPort int port;
    @Autowired GuestIdentityService identities;
    @Autowired JdbcTemplate jdbc;
    @Autowired Notifications notices;
    @Autowired DeadlineReminders deadlines;
    @Autowired CareerProfileService profiles;
    @Autowired CareerTargetPlans plans;
    @Autowired com.offerpilot.job.application.JobRecommendationService recommendations;
    @Autowired com.offerpilot.interview.application.InterviewService interviews;
    @MockBean ProfileEvidenceSource evidence;
    @MockBean TargetJobSearch search;
    String alice,bob,owner;
    @BeforeEach void users() { alice=identities.create().accessToken();bob=identities.create().accessToken();owner=identities.require("Bearer "+alice); }
    HttpResponse<String> request(String method,String path,String token,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Content-Type","application/json");
        if(token!=null) builder.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);return JSON.readTree(response.body()).path("data");
    }
    String app(String deadline,String status) {
        String id=UUID.randomUUID().toString(), job="job-"+id;
        jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload) VALUES (?,'notification-fixture',?,'campus',?::jsonb)",job,id,JSON.createObjectNode().put("title","Fixture position").put("deadline",deadline).toString());
        jdbc.update("INSERT INTO applications(id,user_id,job_id,status) VALUES (?,?,?,?)",id,owner,job,status);return id;
    }
    @Test void deliveryReadCountPaginationAndOwnerIsolation() throws Exception {
        for(String path:List.of("/notifications","/notifications/unread-count","/career/target-plan","/career/profile-suggestions")) assertThat(request("GET",path,null,null).statusCode()).isEqualTo(401);
        assertThat(request("PUT","/notifications/read-all",null,null).statusCode()).isEqualTo(401);
        assertThat(request("PUT","/notifications/x/read",null,null).statusCode()).isEqualTo(401);
        assertThat(request("POST","/career/target-plan/confirm",null,Map.of("revision","r")).statusCode()).isEqualTo(401);
        assertThat(request("POST","/career/target-plan/retry",null,Map.of("revision","r")).statusCode()).isEqualTo(401);
        assertThat(request("POST","/career/profile-suggestions/x/decision",null,Map.of("accept",true,"skills",List.of())).statusCode()).isEqualTo(401);
        for(int i=0;i<23;i++) notices.send(owner,"NEW_JOB","fixture:"+i,"Fixture notice","Fixture body","/jobs");
        notices.send(owner,"NEW_JOB","fixture:0","Duplicate","","/jobs");
        assertThat(data(request("GET","/notifications/unread-count",alice,null)).asInt()).isEqualTo(23);
        var first=data(request("GET","/notifications?page=1&size=20",alice,null));assertThat(first.path("items")).hasSize(20);
        assertThat(data(request("GET","/notifications?page=2&size=20",alice,null)).path("items")).hasSize(3);
        String id=first.path("items").get(0).path("id").asText();
        assertThat(request("PUT","/notifications/"+id+"/read",bob,null).statusCode()).isEqualTo(404);
        data(request("PUT","/notifications/"+id+"/read",alice,null));data(request("PUT","/notifications/"+id+"/read",alice,null));
        assertThat(data(request("GET","/notifications/unread-count",alice,null)).asInt()).isEqualTo(22);
        data(request("PUT","/notifications/read-all",alice,null));assertThat(notices.unread(owner)).isZero();
        assertThat(data(request("GET","/notifications",bob,null)).path("items")).isEmpty();
        assertThat(request("GET","/notifications?page=0",alice,null).statusCode()).isEqualTo(400);
    }
    @Test void repeatedAndConcurrentDeadlineScansAreIdempotentAndReschedulingHasNewKey() throws Exception {
        String active=app("2026-10-23","INTERVIEW");app("2026-10-23","CLOSED");app("2026-10-23","OFFER");app("2026-02-30","TODO");app("长期有效","TODO");
        jdbc.update("UPDATE applications SET next_action='准备面试',next_action_at='2026-10-21' WHERE id=?",active);
        var pool=Executors.newFixedThreadPool(2);
        try { var one=pool.submit(()->deadlines.remind(LocalDate.of(2026,10,20)));var two=pool.submit(()->deadlines.remind(LocalDate.of(2026,10,20)));one.get();two.get(); } finally { pool.shutdownNow(); }
        assertThat(notices.list(owner,1,50).items()).hasSize(2);
        deadlines.remind(LocalDate.of(2026,10,22));deadlines.remind(LocalDate.of(2026,10,22));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND kind='DDL'",Integer.class,owner)).isEqualTo(2);
        jdbc.update("UPDATE jobs SET payload=jsonb_set(payload,'{deadline}','\"2026-10-25\"') WHERE id=(SELECT job_id FROM applications WHERE id=?)",active);
        deadlines.remind(LocalDate.of(2026,10,22));assertThat(notices.list(owner,1,50).items()).hasSize(4);
    }
    @Test void realStoredJobRecommendationsAndSavedInterviewPlansProduceDeduplicatedNotices() throws Exception {
        String application=app("2099-12-31","TODO");
        String job=jdbc.queryForObject("SELECT job_id FROM applications WHERE id=?",String.class,application);
        jdbc.update("UPDATE jobs SET payload=jsonb_set(payload,'{title}','\"SQL Fixture position\"') WHERE id=?",job);
        profiles.save(owner,"SQL","上海","SQL",List.of());
        recommendations.daily(owner);recommendations.daily(owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND dedupe_key=?",Integer.class,owner,"recommended-job:"+job)).isEqualTo(1);
        var session=interviews.create(owner,"数据分析师","表达与结构");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND dedupe_key=?",Integer.class,owner,"interview-created:"+session.id())).isEqualTo(1);
    }

    @Test void targetChangeQueuesSearchesAndRequiresCurrentRevisionConfirmation() throws Exception {
        when(search.search(eq(owner),anyString(),anyString())).thenReturn(JSON.readTree("{\"items\":[],\"meta\":{\"partial\":false}}"));
        profiles.save(owner,"软件开发工程师","上海","Java",List.of());
        // Simulate an unchanged target that predates V47; an explicit adoption fills the missing queue.
        jdbc.update("DELETE FROM career_target_plans WHERE user_id=?",owner);
        profiles.saveTarget(owner,"软件开发工程师");
        var first=plans.get(owner);assertThat(first.status()).isEqualTo("PENDING");
        plans.generate(owner);plans.generate(owner);verify(search,times(1)).search(owner,"软件开发工程师","上海");
        assertThat(plans.get(owner).result().path("skillGap")).isNotEmpty();assertThat(plans.get(owner).result().path("learning")).isNotEmpty();
        data(request("POST","/career/target-plan/confirm",alice,Map.of("revision",first.revision())));
        assertThat(plans.get(owner).status()).isEqualTo("CONFIRMED");
        profiles.saveTarget(owner,"产品经理");
        assertThat(request("POST","/career/target-plan/confirm",alice,Map.of("revision",first.revision())).statusCode()).isEqualTo(409);
        assertThat(data(request("GET","/career/target-plan",bob,null)).isMissingNode()).isTrue();
        // A result arriving after a second target change is discarded, including its notification.
        doAnswer(c->{profiles.saveTarget(owner,"数据分析师");return JSON.readTree("{\"items\":[]}");}).when(search).search(owner,"产品经理","上海");
        plans.generate(owner);assertThat(plans.get(owner).target()).isEqualTo("数据分析师");assertThat(plans.get(owner).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND kind='CAREER_TARGET'",Integer.class,owner)).isEqualTo(1);
    }
    @Test void proposalsAreSourcePinnedConfirmedExplicitlyAndPreserveOtherProfileFields() throws Exception {
        var source=new ProfileEvidenceSource.Observation("RESUME","resume","version-1",List.of("Java"),List.of("真实夹具技能原文"));
        when(evidence.read(owner)).thenReturn(List.of(source));
        profiles.save(owner,"产品经理","上海","SQL",List.of("研究"),"专业","课程","价值观");
        var list=data(request("GET","/career/profile-suggestions",alice,null));String id=list.get(0).path("id").asText();
        data(request("GET","/career/profile-suggestions",alice,null));assertThat(profiles.get(owner).skills()).isEqualTo("SQL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND kind='PROFILE_UPDATE'",Integer.class,owner)).isEqualTo(1);
        assertThat(request("POST","/career/profile-suggestions/"+id+"/decision",bob,Map.of("accept",true,"skills",List.of("Java"))).statusCode()).isEqualTo(404);
        assertThat(request("POST","/career/profile-suggestions/"+id+"/decision",alice,Map.of("accept",true,"skills",List.of("Invented"))).statusCode()).isEqualTo(400);
        var accepted=data(request("POST","/career/profile-suggestions/"+id+"/decision",alice,Map.of("accept",true,"skills",List.of("Java"))));
        assertThat(accepted.path("skills").asText()).isEqualTo("SQL\nJava");assertThat(accepted.path("major").asText()).isEqualTo("专业");assertThat(accepted.path("evidence").get(0).path("sourceType").asText()).isEqualTo("RESUME");
        data(request("POST","/career/profile-suggestions/"+id+"/decision",alice,Map.of("accept",true,"skills",List.of("Java"))));
        when(evidence.read(owner)).thenReturn(List.of(new ProfileEvidenceSource.Observation("GITHUB","account","snapshot",List.of("Python"),List.of("非fork仓库及本人提交"))));
        String stale=data(request("GET","/career/profile-suggestions",alice,null)).get(0).path("id").asText();
        when(evidence.read(owner)).thenReturn(List.of());
        assertThat(request("POST","/career/profile-suggestions/"+stale+"/decision",alice,Map.of("accept",true,"skills",List.of("Python"))).statusCode()).isEqualTo(409);
        jdbc.update("DELETE FROM users WHERE id=?",owner);
        for(String table:List.of("notifications","career_target_plans","career_profile_suggestions")) assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE user_id=?",Integer.class,owner)).isZero();
    }
}
