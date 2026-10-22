package com.offerpilot;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.generation.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP/JWT/Flyway/PostgreSQL lifecycle; no external model calls. */
class BackgroundGenerationHttpIntegrationTest extends SharedHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired GuestIdentityService guests;
    @Autowired JdbcTemplate jdbc;
    @Autowired GenerationTaskService service;
    @SpyBean GenerationWork work;
    static final ObjectMapper JSON=new ObjectMapper();
    HttpRequest request(String method,String path,String token,String body) {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/generation-tasks"+path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if(token!=null) b.header("Authorization","Bearer "+token);
        return b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
    }
    HttpResponse<String> send(String method,String path,String token,String body) throws Exception {
        return HttpClient.newHttpClient().send(request(method,path,token,body),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response) throws Exception { assertThat(response.statusCode()).isEqualTo(200);return JSON.readTree(response.body()).path("data"); }
    String body(String request) throws Exception {
        return JSON.writeValueAsString(Map.of("kind","JOB_IMPORT","entityId","current","requestId",request,"input",Map.of("text","岗位名称：Java开发工程师\n公司：测试企业\n岗位职责：负责服务开发和系统维护工作\n任职要求：熟悉Java开发与数据库设计")));
    }
    String status(String owner,String id) {return service.get(owner,id).status();}

    @Test void acceptedHttpDisconnectKeepsRunningAndRestoresAnOwnedPersistedDraft() throws Exception {
        var guest=guests.create();String token=guest.accessToken(),owner=guests.require("Bearer "+token);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout");return call.callRealMethod(); })
                .when(work).execute(eq(owner),any(),any());
        String id;
        try {
            var response=HttpClient.newHttpClient().send(request("POST","",token,body(UUID.randomUUID().toString())),HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            id=JSON.readTree(response.body().readAllBytes()).path("data").path("id").asText();
            response.body().close(); // Starting HTTP exchange ends while model work is deliberately held.
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(status(owner,id)).isEqualTo("RUNNING");
            var stranger=guests.create();
            assertThat(send("GET","/"+id,stranger.accessToken(),null).statusCode()).isEqualTo(404);
            assertThat(send("GET","/"+id+"/result",stranger.accessToken(),null).statusCode()).isEqualTo(404);
            assertThat(send("POST","/"+id+"/cancel",stranger.accessToken(),null).statusCode()).isEqualTo(404);
            assertThat(send("GET","",stranger.accessToken(),null).body()).doesNotContain(id);
            assertThat(send("GET","/"+id,null,null).statusCode()).isEqualTo(401);
            release.countDown();await().atMost(Duration.ofSeconds(10)).until(() -> status(owner,id).equals("SUCCEEDED"));
            var saved=data(send("GET","/"+id+"/result",token,null));
            assertThat(saved.path("draft").path("title").asText()).isEqualTo("Java开发工程师");
            assertThat(data(send("GET","?kind=JOB_IMPORT&entityId=current",token,null)).toString()).contains(id,"SUCCEEDED","结果已保存");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE user_id=?",Integer.class,owner)).isZero(); // review only
            assertThat(service.get(owner,id).resultId()).isEqualTo(id);
            assertThat(service.get(owner,id).startedAt()).isNotNull();assertThat(service.get(owner,id).finishedAt()).isNotNull();
        } finally { release.countDown(); }
    }

    @Test void cancelIsOwnerScopedIdempotentAndLateOutputCannotOverwriteIt() throws Exception {
        var guest=guests.create();String token=guest.accessToken(),owner=guests.require("Bearer "+token);
        var entered=new CountDownLatch(1);var stopped=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown();try {new CountDownLatch(1).await(10,TimeUnit.SECONDS);return call.callRealMethod();}finally {stopped.countDown();} })
                .when(work).execute(eq(owner),any(),any());
        String id=data(send("POST","",token,body(UUID.randomUUID().toString()))).path("id").asText();
        assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(send("POST","/"+id+"/cancel",token,null).statusCode()).isEqualTo(200);
        assertThat(stopped.await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(send("POST","/"+id+"/cancel",token,null).statusCode()).isEqualTo(200);
        assertThat(status(owner,id)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT result IS NULL FROM generation_tasks WHERE id=?",Boolean.class,id)).isTrue();
        assertThat(send("GET","/"+id+"/result",token,null).statusCode()).isEqualTo(409);
    }

    @Test void failedJobsAreHonestAndIdempotencyRejectsChangedInput() throws Exception {
        var guest=guests.create();String token=guest.accessToken(),owner=guests.require("Bearer "+token);
        doThrow(new IllegalStateException("private-provider-diagnostic")).when(work).execute(eq(owner),any(),any());
        String body=body(UUID.randomUUID().toString());String id=data(send("POST","",token,body)).path("id").asText();
        await().atMost(Duration.ofSeconds(5)).until(() -> status(owner,id).equals("FAILED"));
        assertThat(data(send("POST","",token,body)).path("id").asText()).isEqualTo(id);
        assertThat(send("GET","/"+id,token,null).body()).doesNotContain("private-provider-diagnostic");
        assertThat(send("POST","",token,body.replace("Java开发工程师","Python开发工程师")).statusCode()).isEqualTo(400);
        verify(work,times(1)).execute(eq(owner),any(),any());
        // Compatibility: a page may cancel a restored domain report through its original endpoint.
        // Its application service emits GENERATION_CANCELLED, which must not become a failed task.
        doThrow(new com.offerpilot.common.error.BusinessException(com.offerpilot.common.error.ErrorCode.GENERATION_CANCELLED))
                .when(work).execute(eq(owner),any(),any());
        String cancelled=data(send("POST","",token,body(UUID.randomUUID().toString()))).path("id").asText();
        await().atMost(Duration.ofSeconds(5)).until(() -> status(owner,cancelled).equals("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT result IS NULL FROM generation_tasks WHERE id=?",Boolean.class,cancelled)).isTrue();
    }

    @Test void boundedWorkersLeaveExcessWorkQueuedAndQueuedCancelNeverStartsIt() throws Exception {
        var entered=new CountDownLatch(4);var release=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown();if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout");return call.callRealMethod(); })
                .when(work).execute(anyString(),any(),any());
        var owners=new ArrayList<String>();var ids=new ArrayList<String>();
        try {
            for(int i=0;i<4;i++) {
                var guest=guests.create();String owner=guests.require("Bearer "+guest.accessToken());owners.add(owner);
                ids.add(data(send("POST","",guest.accessToken(),body(UUID.randomUUID().toString()))).path("id").asText());
            }
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var fifth=guests.create();String fifthOwner=guests.require("Bearer "+fifth.accessToken());
            String queued=data(send("POST","",fifth.accessToken(),body(UUID.randomUUID().toString()))).path("id").asText();
            assertThat(status(fifthOwner,queued)).isEqualTo("QUEUED");
            assertThat(send("POST","/"+queued+"/cancel",fifth.accessToken(),null).statusCode()).isEqualTo(200);
            release.countDown();
            for(int i=0;i<4;i++) { final int n=i;await().atMost(Duration.ofSeconds(10)).until(() -> status(owners.get(n),ids.get(n)).equals("SUCCEEDED")); }
            assertThat(status(fifthOwner,queued)).isEqualTo("CANCELLED");
            verify(work,never()).execute(eq(fifthOwner),any(),any());
        } finally { release.countDown(); }
    }

    @Test void bootRecoveryFailsQueuedAndRunningRowsButKeepsTerminalResults() throws Exception {
        var guest=guests.create();String owner=guests.require("Bearer "+guest.accessToken());
        var ids=new ArrayList<String>();
        for(String state:List.of("QUEUED","RUNNING","SUCCEEDED","CANCELLED")) {
            String id="recovery_"+UUID.randomUUID();ids.add(id);
            jdbc.update("INSERT INTO generation_tasks(id,user_id,kind,entity_id,request_id,request_hash,status,stage,title,href,result) VALUES (?,?,'JOB_IMPORT','current',?,'fixture',?,'fixture','fixture','/jobs','{}')",id,owner,UUID.randomUUID().toString(),state);
        }
        service.recoverInterrupted();
        assertThat(ids.stream().map(id -> status(owner,id))).containsExactly("FAILED","FAILED","SUCCEEDED","CANCELLED");
        assertThat(service.get(owner,ids.get(1)).error()).contains("服务重启");
    }
}
