package com.offerpilot;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.auth.application.GuestIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Actual owner-authenticated HTTP and V50 PostgreSQL. Legacy report rules require no model IO. */
class InterviewPlanUpdatesIntegrationTest extends SharedHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired GuestIdentityService guests;
    @Autowired JdbcTemplate jdbc;
    final ObjectMapper json=new ObjectMapper();
    final HttpClient client=HttpClient.newHttpClient();
    HttpResponse<String> call(String method,String path,String token,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Content-Type","application/json");
        if(token!=null) request.header("Authorization","Bearer "+token);
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }
    String report(String token,String role) throws Exception {
        return report(token,role,null);
    }
    String report(String token,String role,String jobId) throws Exception {
        var body=new HashMap<String,Object>(); body.put("role",role); body.put("kind","技术");
        if(jobId!=null) body.put("jobId",jobId);
        String id=data(call("POST","/interviews",token,body)).path("id").asText();
        data(call("PUT","/interviews/"+id+"/answers/0",token,Map.of("answerText","我负责接口设计。")));
        data(call("PATCH","/interviews/"+id,token,Map.of("completed",true)));
        return id;
    }
    @Test void explicitJobIdentityWinsOverRoleLabels() throws Exception {
        String token=guests.create().accessToken(),a="cp05_"+UUID.randomUUID(),b="cp05_"+UUID.randomUUID();
        for(String job:List.of(a,b)) jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload) VALUES (?,'cp05',?,'intern','{\"title\":\"合成后端岗位\"}')",job,job);
        String original=report(token,"后端",a),same=report(token,"Java后端",a),different=report(token,"后端",b),unspecified=report(token,"后端");
        String base="/career/learning-plans";
        var plan=data(call("POST",base+"/from-interview/"+original,token,null));
        assertThat(data(call("GET",base+"/updates/from-interview/"+same,token,null)).get(0).path("planId")).isEqualTo(plan.path("id"));
        assertThat(data(call("GET",base+"/updates/from-interview/"+different,token,null))).isEmpty();
        assertThat(data(call("GET",base+"/updates/from-interview/"+unspecified,token,null))).isEmpty();
    }
    @Test void previewNeverWritesConcurrentConfirmIsIdempotentAndIsolationAndStaleChecksHold() throws Exception {
        String token=guests.create().accessToken(),other=guests.create().accessToken(),owner=guests.require("Bearer "+token);
        String first=report(token,"后端"),next=report(token,"后端"),unrelated=report(token,"前端");
        String base="/career/learning-plans";
        String plan=data(call("POST",base+"/from-interview/"+first,token,null)).path("id").asText();
        String before=jdbc.queryForObject("SELECT roadmap::text FROM interview_learning_plans WHERE id=?",String.class,plan);
        String preview=base+"/updates/from-interview/"+next;
        assertThat(call("GET",preview,null,null).statusCode()).isEqualTo(401);
        assertThat(call("GET",preview,other,null).statusCode()).isEqualTo(404);
        assertThat(data(call("GET",base+"/updates/from-interview/"+unrelated,token,null))).isEmpty();
        var proposal=data(call("GET",preview,token,null)).get(0);
        assertThat(proposal.path("planId").asText()).isEqualTo(plan);
        assertThat(jdbc.queryForObject("SELECT roadmap::text FROM interview_learning_plans WHERE id=?",String.class,plan)).isEqualTo(before);
        String confirm=base+"/"+plan+"/updates/from-interview/"+next+"/confirm";
        var body=Map.of("fingerprint",proposal.path("fingerprint").asText());
        assertThat(call("POST",confirm,other,body).statusCode()).isEqualTo(404);
        assertThat(call("POST",confirm,token,Map.of("fingerprint","0".repeat(64))).statusCode()).isEqualTo(409);
        var pool=Executors.newFixedThreadPool(2); var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        try {
            Callable<JsonNode> write=() -> {ready.countDown();assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();return data(call("POST",confirm,token,body));};
            var a=pool.submit(write);var b=pool.submit(write);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(a.get(30,TimeUnit.SECONDS)).isEqualTo(b.get(30,TimeUnit.SECONDS));
        } finally { start.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
        var saved=data(call("GET",base,token,null)).get(0).path("roadmap");
        assertThat(saved).isNotEqualTo(json.readTree(before));
        for(var week:saved.path("weeks")) for(var item:week.path("items")) assertThat(item.path("done").asBoolean()).isFalse();
        assertThat(data(call("GET",preview,token,null))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_task_completions WHERE user_id=?",Integer.class,owner)).isZero();
        assertThat(data(call("GET",base,other,null))).isEmpty();
        // A report edit invalidates its saved report/source and the previous confirmation token.
        data(call("PUT","/interviews/"+next+"/answers/0",token,Map.of("answerText","当时我负责任务。我设计事务，结果完成回滚。")));
        assertThat(call("POST",confirm,token,body).statusCode()).isEqualTo(409);
    }
}
