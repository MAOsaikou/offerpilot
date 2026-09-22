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
    "spring.ai.deepseek.api-key=jobs-test-only","offerpilot.llm.default-tier=CHEAP",
    "spring.ai.mcp.server.enabled=false","offerpilot.resume.worker-enabled=false"})
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
    }
    @Test void fabricatedEvidenceFailsWithoutSavingAReport() throws Exception {
        String token=identities.create().accessToken(), resume=seed(token), job=jobId(token); badEvidence=true;
        assertThat(request("POST","/jobs/"+job+"/matches",token,Map.of("resumeId",resume,"resumeVersionId",resume,"requestId",UUID.randomUUID().toString())).statusCode()).isEqualTo(422);
        var history=data(request("GET","/jobs/"+job+"/matches",token,null)); assertThat(history.get(0).path("status").asText()).isEqualTo("FAILED");
    }
    @Test void authenticationAndInputValidationBeforeTools() throws Exception {
        assertThat(request("GET","/jobs?query=Java",null,null).statusCode()).isEqualTo(401);
        String token=identities.create().accessToken();
        assertThat(request("GET","/jobs?query=Java&nature=invalid",token,null).statusCode()).isEqualTo(400);
        assertThat(request("GET","/jobs?query=",token,null).statusCode()).isEqualTo(400);
        assertThat(request("GET","/jobs/unknown",token,null).statusCode()).isEqualTo(404);
    }

    @Test void totalUpstreamFailureIsNotAnEmptySearch() throws Exception {
        allSitesFailed=true;
        assertThat(request("GET","/jobs?query=Java",identities.create().accessToken(),null).statusCode()).isEqualTo(502);
    }

    @Test void activeMatchBlocksAnotherRequestAndExpiredRunCanRecover() throws Exception {
        String token=identities.create().accessToken(), resume=seed(token), job=jobId(token);
        String owner=identities.require("Bearer "+token), run=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO job_matches(id,user_id,job_id,resume_version_id,request_id,status) VALUES (?,?,?,?,?,'RUNNING')",run,owner,job,resume,UUID.randomUUID().toString());
        var body=Map.of("resumeId",resume,"resumeVersionId",resume,"requestId",UUID.randomUUID().toString());
        assertThat(request("POST","/jobs/"+job+"/matches",token,body).statusCode()).isEqualTo(429);
        jdbc.update("UPDATE job_matches SET created_at=now()-interval '4 minutes' WHERE id=?",run);
        assertThat(data(request("POST","/jobs/"+job+"/matches",token,body)).path("score").asInt()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT status FROM job_matches WHERE id=?",String.class,run)).isEqualTo("FAILED");
    }
}
