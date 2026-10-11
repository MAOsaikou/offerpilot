package com.offerpilot;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.offerpilot.auth.application.GuestIdentityService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.ai.model.chat=deepseek","spring.ai.model.embedding=none","spring.ai.retry.max-attempts=1",
    "spring.ai.deepseek.api-key=jobs-test-only","offerpilot.llm.default-tier=FLASH",
    "offerpilot.jobs.source-mode=LIVE","spring.ai.mcp.server.enabled=false","offerpilot.resume.worker-enabled=false"})
@ActiveProfiles("test") @Testcontainers
class JobsHttpIntegrationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    static final ObjectMapper JSON=new ObjectMapper();
    static final AtomicReference<String> modelInput=new AtomicReference<>();
    static volatile boolean badEvidence=false;
    static volatile boolean allSitesFailed=false;
    static final List<String> methods=Collections.synchronizedList(new ArrayList<>());
    static final HttpServer upstream=startUpstream();
    @LocalServerPort int port;
    @Autowired GuestIdentityService identities;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.mock.mockito.SpyBean com.offerpilot.resume.application.ResumeOptimizer optimizer;
    @Autowired com.offerpilot.common.artifact.JobArtifacts artifacts;
    @org.springframework.boot.test.mock.mockito.SpyBean com.offerpilot.llm.ModelGateway gateway;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",db::getJdbcUrl); p.add("spring.datasource.username",db::getUsername); p.add("spring.datasource.password",db::getPassword);
        p.add("offerpilot.mcp.jobs-url",()->"http://127.0.0.1:"+upstream.getAddress().getPort());
        p.add("spring.ai.deepseek.base-url",()->"http://127.0.0.1:"+upstream.getAddress().getPort());
    }
    static HttpServer startUpstream() {
        try {
            var s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            s.createContext("/mcp",x->{
                JsonNode body=JSON.readTree(x.getRequestBody()); String method=body.path("method").asText(); methods.add(method);
                if("notifications/initialized".equals(method)) { x.sendResponseHeaders(202,-1); x.close(); return; }
                Object result;
                if("initialize".equals(method)) result=Map.of("protocolVersion","2025-03-26","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","fixture","version","1"));
                else {
                    String tool=body.path("params").path("name").asText(); methods.add(tool);
                    Object payload=switch(tool) {
                        case "list_sites" -> Map.of("sites",List.of(Map.of("id","fixture","name","测试企业","supportedNatures",List.of("campus"))));
                        case "search_jobs" -> allSitesFailed
                            ? Map.of("jobs",List.of(),"_meta",Map.of("sites",List.of("offline"),"skipped",List.of(Map.of("site","offline","reason","timeout"))))
                            : Map.of("jobs",List.of(job(),job()),"_meta",Map.of("sites",List.of("fixture","offline"),"skipped",List.of(Map.of("site","offline","reason","timeout")),"cached",false,"degradedSites",List.of(),"truncated",false));
                        default -> Map.of("job",job());
                    };
                    result=Map.of("content",List.of(Map.of("type","text","text",JSON.writeValueAsString(payload))));
                }
                byte[] out=JSON.writeValueAsBytes(Map.of("jsonrpc","2.0","id",body.path("id").asInt(),"result",result));
                x.getResponseHeaders().set("Content-Type","application/json"); x.sendResponseHeaders(200,out.length); x.getResponseBody().write(out); x.close();
            });
            s.createContext("/chat/completions",x->{
                modelInput.set(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                String output="{\"summary\":\"Java项目提供基础证据\",\"requirements\":[{\"requirement\":\"Java\",\"status\":\"MET\",\"jdQuote\":\"熟悉Java\",\"resumePath\":\"/summary\",\"resumeQuote\":\""+(badEvidence?"虚构经历":"Java项目")+"\",\"reason\":\"简历有项目经历\",\"suggestion\":\"准备项目细节\"}]}";
                String system=JSON.readTree(modelInput.get()).path("messages").get(0).path("content").asText();
                if(system.contains("意图识别与规划 v1")) output="{\"kind\":\"single\",\"expert\":\"JOB\",\"confidence\":1}";
                else if(system.contains("岗位动作与搜索参数解析 v1")) {
                    var messages=JSON.readTree(modelInput.get()).path("messages");
                    String last=messages.get(messages.size()-1).path("content").asText();
                    output=last.contains("请匹配选定版本") ? "{\"action\":\"match\"}"
                            : "{\"action\":\"search\",\"query\":\"Java\",\"site\":\"测试企业\",\"nature\":\"campus\",\"location\":\"上海\"}";
                } else if(!system.contains("求职材料匹配助手")) output="请查看本轮工具结果；岗位与匹配均以卡片中的原文证据为准。";
                String frame="data: "+JSON.writeValueAsString(Map.of("id","test","object","chat.completion.chunk","created",1,"model","deepseek-flash","choices",List.of(Map.of("index",0,"delta",Map.of("role","assistant","content",output)))))+"\n\ndata: [DONE]\n\n";
                byte[] out=frame.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type","text/event-stream");x.sendResponseHeaders(200,out.length);x.getResponseBody().write(out);x.close();
            });
            s.start(); return s;
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    static Map<String,Object> job() { return Map.of("id","external-1","site","fixture","title","Java工程师","company","测试企业","locations","上海","description","开发业务系统","requirement","熟悉Java","url","javascript:alert(1)"); }
    @AfterAll static void stop() { upstream.stop(0); }
    @BeforeEach void reset() { badEvidence=false; allSitesFailed=false; }
    HttpResponse<String> request(String method,String path,String token,Object body) throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Content-Type","application/json").timeout(java.time.Duration.ofSeconds(35));
        if(token!=null)r.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> r) throws Exception { assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200); return JSON.readTree(r.body()).path("data"); }
    String seed(String token) {
        String owner=identities.require("Bearer "+token), key=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES (?,?,'RESUME','test',?,'fake.txt','text/plain',1,'test')",key,owner,key);
        jdbc.update("INSERT INTO resumes(id,user_id,title,source_file_id,parse_status) VALUES (?,?,'测试简历',?,'READY')",key,owner,key);
        jdbc.update("INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text) VALUES (?,?,?,1,'UPLOAD',?::jsonb,'Java项目')",key,key,owner,"{\"basics\":{\"email\":\"secret@example.test\"},\"summary\":\"完成Java项目\"}");
        jdbc.update("UPDATE resumes SET current_version_id=? WHERE id=?",key,key); return key;
    }
    String jobId(String token) throws Exception { return data(request("GET","/jobs?query=Java",token,null)).path("items").get(0).path("id").asText(); }
    @Test void realHttpChainUsesMcpDeduplicatesAndPersistsDetails() throws Exception {
        String token=identities.create().accessToken();
        data(request("GET","/jobs/sites",token,null));
        var search=data(request("GET","/jobs?query=Java",token,null));
        assertThat(search.path("items").size()).isEqualTo(1); assertThat(search.path("meta").path("skipped").size()).isEqualTo(1);
        String id=search.path("items").get(0).path("id").asText();
        var detail=data(request("POST","/jobs/"+id+"/refresh",token,null));
        assertThat(detail.path("requirement").asText()).isEqualTo("熟悉Java"); assertThat(detail.path("url").asText()).isEmpty();
        assertThat(data(request("GET","/jobs/"+id,token,null)).path("detailFetchedAt").isTextual()).isTrue();
        assertThat(methods).contains("initialize","notifications/initialized","tools/call","search_jobs","get_job_detail","list_sites");
    }
    @Test void matchIsVersionBoundIdempotentAndPrivate() throws Exception {
        String token=identities.create().accessToken(), other=identities.create().accessToken(), resume=seed(token), job=jobId(token);
        var body=Map.of("resumeId",resume,"resumeVersionId",resume,"requestId",UUID.randomUUID().toString());
        var report=data(request("POST","/jobs/"+job+"/matches",token,body));
        assertThat(report.path("score").asInt()).isEqualTo(100); assertThat(report.path("resumeVersionId").asText()).isEqualTo(resume);
        assertThat(modelInput.get()).doesNotContain("secret@example.test");
        assertThat(data(request("POST","/jobs/"+job+"/matches",token,body)).path("id")).isEqualTo(report.path("id"));
        assertThat(data(request("GET","/jobs/matches/"+report.path("id").asText(),token,null))).isEqualTo(report);
        assertThat(request("GET","/jobs/matches/"+report.path("id").asText(),other,null).statusCode()).isEqualTo(404);
        assertThat(request("POST","/jobs/"+job+"/matches",other,body).statusCode()).isEqualTo(404);
        assertThat(data(request("GET","/jobs/"+job+"/matches",other,null))).isEmpty();
        String exportPath="/jobs/matches/"+report.path("id").asText()+"/eval.md";
        var export=request("GET",exportPath,token,null);
        assertThat(export.statusCode()).withFailMessage(export.body()).isEqualTo(200);
        assertThat(export.headers().firstValue("Content-Type").orElse("")).contains("text/markdown");
        assertThat(export.body()).contains("Java工程师", "测试企业", "要求覆盖", "不是录用预测");
        assertThat(request("GET",exportPath,other,null).statusCode()).isEqualTo(404);
        assertThat(request("GET",exportPath,null,null).statusCode()).isEqualTo(401);
        assertThat(request("GET","/jobs/matches/missing/eval.md",token,null).statusCode()).isEqualTo(404);

    }
    @Test void controlledSlowProRequestKeepsOwnershipAndSavesExactlyOnce() throws Exception {
        String token=identities.create().accessToken(), other=identities.create().accessToken();
        String resume=seed(token), job=jobId(token), requestId=UUID.randomUUID().toString();
        var subscribed=new java.util.concurrent.CountDownLatch(1);
        var sink=reactor.core.publisher.Sinks.<org.springframework.ai.chat.model.ChatResponse>one();
        org.mockito.Mockito.doReturn(reactor.core.publisher.Flux.defer(() -> {
            subscribed.countDown(); return sink.asMono().flux();
        })).when(gateway).stream(org.mockito.ArgumentMatchers.eq(com.offerpilot.llm.ModelTier.PRO),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(0.0));
        var body=Map.of("resumeId",resume,"resumeVersionId",resume,"requestId",requestId,"modelTier","PRO");
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/jobs/"+job+"/matches"))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        var pending=HttpClient.newHttpClient().sendAsync(req,HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(subscribed.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(pending).isNotDone();
            var history=data(request("GET","/jobs/"+job+"/matches",token,null));
            assertThat(history.get(0).path("status").asText()).isEqualTo("RUNNING");
            assertThat(data(request("GET","/jobs/"+job+"/matches",other,null))).isEmpty();
            assertThat(request("POST","/jobs/"+job+"/matches",other,body).statusCode()).isEqualTo(404);
            assertThat(request("POST","/jobs/"+job+"/matches",token,body).statusCode()).isEqualTo(429);
            String output="{\"summary\":\"依据\",\"requirements\":[{\"requirement\":\"Java\",\"status\":\"MET\",\"jdQuote\":\"熟悉Java\",\"resumePath\":\"/summary\",\"resumeQuote\":\"Java项目\",\"reason\":\"原文证据\",\"suggestion\":\"准备细节\"}]}";
            sink.tryEmitValue(new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage(output)))));
            var report=data(pending.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(report.path("modelTier").asText()).isEqualTo("PRO");
            assertThat(data(request("POST","/jobs/"+job+"/matches",token,body))).isEqualTo(report);
            assertThat(data(request("GET","/jobs/"+job+"/matches",token,null))).hasSize(1);
            var changedTier=new HashMap<String,String>(body); changedTier.put("modelTier","FLASH");
            assertThat(request("POST","/jobs/"+job+"/matches",token,changedTier).statusCode()).isEqualTo(400);
            org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(1)).stream(
                    org.mockito.ArgumentMatchers.eq(com.offerpilot.llm.ModelTier.PRO),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(0.0));
            var cancelledUpstream=new java.util.concurrent.CountDownLatch(1);
            var startedAgain=new java.util.concurrent.CountDownLatch(1);
            org.mockito.Mockito.doReturn(reactor.core.publisher.Flux.defer(() -> {
                startedAgain.countDown();
                return reactor.core.publisher.Flux.never().doOnCancel(cancelledUpstream::countDown);
            })).when(gateway).stream(org.mockito.ArgumentMatchers.eq(com.offerpilot.llm.ModelTier.PRO),
                    org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(0.0));
            var cancelBody=new HashMap<String,String>(body); cancelBody.put("requestId",UUID.randomUUID().toString());
            var cancelReq=HttpRequest.newBuilder(req.uri()).header("Authorization","Bearer "+token)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(cancelBody))).build();
            var inFlight=HttpClient.newHttpClient().sendAsync(cancelReq,HttpResponse.BodyHandlers.ofString());
            try {
                assertThat(startedAgain.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                String matchId=data(request("GET","/jobs/"+job+"/matches",token,null)).get(0).path("id").asText();
                assertThat(request("POST","/generation-tasks/JOB_MATCH/"+matchId+"/cancel",token,null).statusCode()).isEqualTo(200);
                var cancelled=inFlight.get(15,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(cancelled.statusCode()).isEqualTo(409);
                assertThat(cancelled.body()).contains("30205","取消").doesNotContain("过期");
                assertThat(cancelledUpstream.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var read=request("GET","/jobs/matches/"+matchId,token,null);
                assertThat(read.statusCode()).isEqualTo(409);
                assertThat(read.body()).contains("30205","取消");
                assertThat(request("GET","/jobs/matches/"+matchId,other,null).statusCode()).isEqualTo(404);
                assertThat(data(request("GET","/jobs/"+job+"/matches",token,null)).toString()).contains("CANCELLED");
                assertThat(request("POST","/generation-tasks/JOB_MATCH/"+matchId+"/cancel",token,null).statusCode()).isEqualTo(200);
                assertThat(request("POST","/jobs/"+job+"/matches",token,cancelBody).statusCode()).isEqualTo(409);
                org.mockito.Mockito.doReturn(reactor.core.publisher.Flux.just(new org.springframework.ai.chat.model.ChatResponse(List.of(
                        new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(output))))))
                        .when(gateway).stream(org.mockito.ArgumentMatchers.eq(com.offerpilot.llm.ModelTier.PRO),
                                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(0.0));
                cancelBody.put("requestId",UUID.randomUUID().toString());
                assertThat(data(request("POST","/jobs/"+job+"/matches",token,cancelBody)).path("score").asInt()).isEqualTo(100);
            } finally { inFlight.cancel(true); }
        } finally { sink.tryEmitError(new IllegalStateException("fixture released")); pending.cancel(true); }
    }

    @Test void fabricatedEvidenceFailsWithoutSavingAReport() throws Exception {
        String token=identities.create().accessToken(), resume=seed(token), job=jobId(token); badEvidence=true;
        assertThat(request("POST","/jobs/"+job+"/matches",token,Map.of("resumeId",resume,"resumeVersionId",resume,"requestId",UUID.randomUUID().toString())).statusCode()).isEqualTo(422);
        var history=data(request("GET","/jobs/"+job+"/matches",token,null)); assertThat(history.get(0).path("status").asText()).isEqualTo("FAILED");
    }
    @Test void matchQuotaFailureKeepsCodeRetryAfterAndDoesNotRepair() throws Exception {
        String token=identities.create().accessToken(), resume=seed(token), job=jobId(token);
        org.mockito.Mockito.doReturn(reactor.core.publisher.Flux.error(new com.offerpilot.llm.quota.LlmQuotaException(
                com.offerpilot.common.error.ErrorCode.LLM_RATE_LIMITED,30))).when(gateway).stream(
                org.mockito.ArgumentMatchers.eq(com.offerpilot.llm.ModelTier.FLASH),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(0.0));
        var response=request("POST","/jobs/"+job+"/matches",token,Map.of("resumeId",resume,"resumeVersionId",resume,
                "requestId",UUID.randomUUID().toString(),"modelTier","FLASH"));
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(JSON.readTree(response.body()).path("code").asInt()).isEqualTo(40011);
        assertThat(response.headers().firstValue("Retry-After")).contains("30");
        assertThat(data(request("GET","/jobs/"+job+"/matches",token,null)).get(0).path("status").asText()).isEqualTo("FAILED");
        org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(1)).stream(
                org.mockito.ArgumentMatchers.eq(com.offerpilot.llm.ModelTier.FLASH),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(0.0));
    }

    @Test void authenticationAndInputValidationBeforeTools() throws Exception {
        assertThat(request("GET","/jobs?query=Java",null,null).statusCode()).isEqualTo(401);
        String token=identities.create().accessToken();
        assertThat(request("GET","/jobs?query=Java&nature=invalid",token,null).statusCode()).isEqualTo(400);
        assertThat(request("GET","/jobs?query=",token,null).statusCode()).isEqualTo(400);
        assertThat(request("GET","/jobs/unknown",token,null).statusCode()).isEqualTo(404);
    }

    @Test void applicationDetailAggregatesOnlyOwnedJobArtifactsAndJournalsStatusChanges() throws Exception {
        String token=identities.create().accessToken(), other=identities.create().accessToken(), resume=seed(token), job=jobId(token);
        var report=data(request("POST","/jobs/"+job+"/matches",token,Map.of("resumeId",resume,"resumeVersionId",resume,"requestId",UUID.randomUUID().toString())));
        org.mockito.Mockito.doAnswer(invocation -> reactor.core.publisher.Mono.fromSupplier(() -> JSON.createObjectNode().put("summary","岗位定向建议").set("patches",JSON.createArrayNode())))
            .when(optimizer).optimize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        String optimizePath="/resumes/"+resume+"/versions/"+resume+"/optimize";
        var rewriteBody=Map.of("requestId",UUID.randomUUID().toString(),"jobDescription","熟悉Java","jobId",job);
        // Real REST rewrite before favoriting; later creation must link this existing artifact once.
        var rewriteResponse=request("POST",optimizePath,token,rewriteBody);
        assertThat(rewriteResponse.statusCode()).withFailMessage(rewriteResponse.body()).isEqualTo(200);
        assertThat(rewriteResponse.body()).contains("run.completed").doesNotContain("\"type\":\"error\"");
        String rewrite=data(request("GET","/resumes/"+resume+"/optimizations",token,null)).get(0).path("id").asText();
        String id=data(request("POST","/applications",token,Map.of("jobId",job))).path("id").asText();
        String interview=data(request("POST","/interviews",token,Map.of("role","Java工程师","kind","项目与经历","jobId",job))).path("id").asText();
        data(request("PUT","/interviews/"+interview+"/answers/0",token,Map.of("answerText","当时在项目中，我负责接口，我实现事务，最终完成验证。")));
        String story=data(request("POST","/star-stories",token,Map.of("sessionId",interview,"questionIndex",0))).path("id").asText();
        // Repeated favorites and GETs must not duplicate links. Unknown jobs never create artifacts.
        data(request("POST","/applications",token,Map.of("jobId",job)));
        assertThat(request("POST",optimizePath,token,Map.of("requestId",UUID.randomUUID().toString(),"jobDescription","熟悉Java","jobId","missing")).statusCode()).isEqualTo(404);
        assertThat(request("POST","/interviews",token,Map.of("role","Java","kind","项目与经历","jobId","missing")).statusCode()).isEqualTo(404);
        assertThat(request("POST",optimizePath,other,rewriteBody).statusCode()).isEqualTo(404);
        assertThatThrownBy(() -> artifacts.selectedJob(identities.require("Bearer "+other),null,id,null)).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        assertThatThrownBy(() -> artifacts.selectedJob(identities.require("Bearer "+other),null,null,report.path("id").asText())).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        // A newer report for the same public job belongs to a different owner.
        String otherResume=seed(other), otherOwner=identities.require("Bearer "+other);
        jdbc.update("INSERT INTO job_matches(id,user_id,job_id,resume_version_id,request_id,status,report,created_at) VALUES (?,?,?,?,?,'COMPLETED',?::jsonb,now()+interval '1 minute')",
                "match_other_"+UUID.randomUUID(),otherOwner,job,otherResume,UUID.randomUUID().toString(),report.toString());
        String otherInterview=data(request("POST","/interviews",other,Map.of("role","Java工程师","kind","项目与经历","jobId",job))).path("id").asText();
        var otherRewrite=request("POST","/resumes/"+otherResume+"/versions/"+otherResume+"/optimize",other,Map.of("requestId",UUID.randomUUID().toString(),"jobDescription","熟悉Java","jobId",job));
        assertThat(otherRewrite.statusCode()).isEqualTo(200);
        // Same JD/role but no explicit provenance remains unlinked.
        String owner=identities.require("Bearer "+token);
        jdbc.update("INSERT INTO resume_optimizations(id,user_id,resume_id,version_id,request_id,status,model_tier,job_description) VALUES (?,?,?,?,?,'FAILED','FLASH','熟悉Java')",UUID.randomUUID().toString(),owner,resume,resume,UUID.randomUUID().toString());
        data(request("POST","/interviews",token,Map.of("role","Java工程师","kind","项目与经历")));
        data(request("PATCH","/applications/"+id,token,Map.of("status","INTERVIEW","nextAction","准备项目复盘","nextActionAt","2026-10-12")));
        var detail=data(request("GET","/applications/"+id,token,null));
        assertThat(detail.path("application").path("status").asText()).isEqualTo("INTERVIEW");
        assertThat(detail.path("latestMatch").path("id")).isEqualTo(report.path("id"));
        assertThat(detail.path("timeline").findValuesAsText("kind")).contains("CREATED","ARTIFACT_LINKED","STATUS_CHANGED");
        assertThat(detail.path("resumeOptimizations").findValuesAsText("id")).containsExactly(rewrite);
        assertThat(detail.path("interviews").findValuesAsText("id")).containsExactly(interview).doesNotContain(otherInterview);
        assertThat(detail.path("starStories").findValuesAsText("id")).containsExactly(story);
        assertThat(java.util.stream.StreamSupport.stream(detail.path("timeline").spliterator(),false).filter(e->"ARTIFACT_LINKED".equals(e.path("kind").asText())).map(e->e.path("artifactType").asText()).toList()).containsExactlyInAnyOrder("JOB_MATCH","RESUME_OPTIMIZATION","INTERVIEW","STAR_STORY");
        assertThat(request("GET","/interviews/"+interview+"/recap.md",token,null).statusCode()).isEqualTo(200);
        assertThat(request("GET","/interviews/"+interview+"/recap.md",other,null).statusCode()).isEqualTo(404);
        assertThat(request("GET","/resumes/optimizations/"+rewrite,other,null).statusCode()).isEqualTo(404);
        assertThat(request("GET","/applications/"+id,other,null).statusCode()).isEqualTo(404);
        assertThat(request("GET","/applications/"+id,null,null).statusCode()).isEqualTo(401);
        assertThat(request("GET","/jobs/matches/"+report.path("id").asText()+"/eval.md",token,null).statusCode()).isEqualTo(200);
        int events=detail.path("timeline").size();
        data(request("PATCH","/applications/"+id,token,Map.of("status","INTERVIEW")));
        assertThat(data(request("GET","/applications/"+id,token,null)).path("timeline")).hasSize(events);
    }

    @Test void boardPersistsAcrossClientsAndIsPrivateAndIdempotent() throws Exception {
        String token=identities.create().accessToken(), other=identities.create().accessToken(), job=jobId(token);
        assertThat(request("GET","/applications",null,null).statusCode()).isEqualTo(401);
        var created=data(request("POST","/applications",token,Map.of("jobId",job)));
        String id=created.path("id").asText();
        assertThat(created.path("status").asText()).isEqualTo("TODO");
        assertThat(created.path("job").path("title").asText()).isEqualTo("Java工程师");
        assertThat(data(request("POST","/applications",token,Map.of("jobId",job))).path("id").asText()).isEqualTo(id);
        data(request("PATCH","/applications/"+id,token,Map.of("status","INTERVIEW")));
        data(request("PATCH","/applications/"+id,token,Map.of("note","周五面试，准备项目复盘")));
        // request() creates a fresh HTTP client each time; no browser-local state participates.
        var restored=data(request("GET","/applications",token,null));
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).path("status").asText()).isEqualTo("INTERVIEW");
        assertThat(restored.get(0).path("note").asText()).isEqualTo("周五面试，准备项目复盘");
        assertThat(data(request("POST","/applications",token,Map.of("jobId",job))).path("status").asText()).isEqualTo("INTERVIEW");
        assertThat(data(request("GET","/applications",other,null))).isEmpty();
        assertThat(request("PATCH","/applications/"+id,other,Map.of("note","越权")).statusCode()).isEqualTo(404);
        assertThat(request("PATCH","/applications/unknown",token,Map.of("status","TODO")).statusCode()).isEqualTo(404);
        assertThat(request("POST","/applications",token,Map.of("jobId","unknown")).statusCode()).isEqualTo(404);
        assertThat(request("PATCH","/applications/"+id,token,Map.of("status","INVALID")).statusCode()).isEqualTo(400);
        assertThat(request("PATCH","/applications/"+id,token,Map.of("note","x".repeat(2001))).statusCode()).isEqualTo(400);
        assertThat(request("PATCH","/applications/"+id,token,Map.of()).statusCode()).isEqualTo(400);
        for(String status:List.of("OFFER","CLOSED","TODO","APPLIED"))
            assertThat(data(request("PATCH","/applications/"+id,token,Map.of("status",status))).path("status").asText()).isEqualTo(status);
        assertThat(data(request("PATCH","/applications/"+id,token,Map.of("note",""))).path("note").asText()).isEmpty();
        assertThat(data(request("POST","/applications",other,Map.of("jobId",job))).path("id").asText()).isNotEqualTo(id);
    }

    @Test void totalUpstreamFailureIsNotAnEmptySearch() throws Exception {
        allSitesFailed=true;
        assertThat(request("GET","/jobs?query=Java",identities.create().accessToken(),null).statusCode()).isEqualTo(502);
    }

    @Test void chatSearchAutoAndJobPersistSnapshotThenMatchExplicitSavedVersion() throws Exception {
        String token=identities.create().accessToken(), resume=seed(token);
        for(String mode:List.of("AUTO","JOB")) {
            var response=request("POST","/agent/chat",token,Map.of("content","找上海Java校招岗位","mode",mode));
            assertThat(response.statusCode()).isEqualTo(200);
            var events=events(response.body());
            assertThat(events.stream().map(e->e.path("type").asText())).contains("run.completed","ui.card");
            var card=events.stream().filter(e->"ui.card".equals(e.path("type").asText())).findFirst().orElseThrow();
            assertThat(card.path("cardType").asText()).isEqualTo("job_card");
            assertThat(card.path("payload").path("meta").path("skipped")).hasSize(1);
            assertThat(card.path("payload").path("criteria").path("site").asText()).isEqualTo("fixture");
            assertThat(response.body()).contains("mcp:jobs-mcp").doesNotContain("placeholder.JOB");
            String conv=events.get(0).path("conversationId").asText();
            var messages=data(request("GET","/conversations/"+conv+"/messages",token,null)).path("items");
            assertThat(messages.toString()).contains(card.path("cardId").asText(),"job_card");
            String job=card.path("payload").path("items").get(0).path("id").asText();
            var context=Map.of("jobId",job,"resumeId",resume,"resumeVersionId",resume);
            var matched=request("POST","/agent/chat",token,Map.of("conversationId",conv,"content","请匹配选定版本","mode","JOB","options",Map.of("context",context)));
            assertThat(matched.body()).contains("matchJobToResume","ui.card","job_match","run.completed");
            var searchAgain=request("POST","/agent/chat",token,Map.of("conversationId",conv,"content","继续找Java校招","mode","JOB","options",Map.of("context",context)));
            assertThat(searchAgain.body()).contains("search_jobs","job_card").doesNotContain("matchJobToResume");
            String stranger=identities.create().accessToken();
            assertThat(request("GET","/conversations/"+conv+"/messages",stranger,null).statusCode()).isEqualTo(404);
        }
    }

    @Test void chatAllSitesFailureIsVisibleWithoutJobCard() throws Exception {
        allSitesFailed=true;
        var response=request("POST","/agent/chat",identities.create().accessToken(),Map.of("content","找Java","mode","JOB"));
        assertThat(response.body()).contains("search_jobs","均不可用").doesNotContain("ui.card");
        assertThat(events(response.body()).stream().filter(e->"tool.result".equals(e.path("type").asText()))).allSatisfy(e->assertThat(e.path("ok").asBoolean()).isFalse());
    }

    List<JsonNode> events(String body) {
        return body.lines().filter(line->line.startsWith("data:")).map(line->{
            try { return JSON.readTree(line.substring(5).strip()); }
            catch(Exception e) { throw new IllegalStateException(e); }
        }).toList();
    }

    @Test void activeMatchBlocksAnotherRequestAndExpiredRunCanRecover() throws Exception {
        String token=identities.create().accessToken(), resume=seed(token), job=jobId(token);
        String owner=identities.require("Bearer "+token), run=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO job_matches(id,user_id,job_id,resume_version_id,request_id,status) VALUES (?,?,?,?,?,'RUNNING')",run,owner,job,resume,UUID.randomUUID().toString());
        var body=Map.of("resumeId",resume,"resumeVersionId",resume,"requestId",UUID.randomUUID().toString());
        assertThat(request("POST","/jobs/"+job+"/matches",token,body).statusCode()).isEqualTo(429);
        jdbc.update("UPDATE job_matches SET created_at=now()-interval '6 minutes' WHERE id=?",run);
        assertThat(data(request("POST","/jobs/"+job+"/matches",token,body)).path("score").asInt()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT status FROM job_matches WHERE id=?",String.class,run)).isEqualTo("FAILED");
    }
}
