package com.offerpilot;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.job.application.*;
import com.offerpilot.llm.ModelGateway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** PostgreSQL/Flyway + real authenticated HTTP; all model/MCP/embedding IO uses stubs. */
class JobsVerticalIntegrationTest extends SharedHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired GuestIdentityService guests;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobLocalSearch local;
    @MockBean JobSource source;
    @MockBean ModelGateway gateway;
    @MockBean JobEmbeddings embeddings;
    static final ObjectMapper JSON=new ObjectMapper();
    @BeforeEach void stubs() {
        when(source.search(anyMap())).thenReturn(JSON.createObjectNode().set("jobs",JSON.createArrayNode()));
        when(gateway.stream(any(),anyList(),anyDouble())).thenThrow(new IllegalStateException("offline stub"));
        when(embeddings.enabled()).thenReturn(false);
    }
    JsonNode call(String method,String path,String token,Object body) {
        try {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path))
                    .header("Authorization","Bearer "+token).header("Content-Type","application/json");
            var response=HttpClient.newHttpClient().send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
            return JSON.readTree(response.body()).path("data");
        } catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    String seed(String owner,String word,String sourceName,boolean privateJob) throws Exception {
        String id="job_"+UUID.randomUUID();
        var payload=JSON.createObjectNode().put("site",privateJob?"imported":"vertical").put("externalId",id).put("nature","intern")
                .put("title",word+"后端开发").put("company","测试企业").put("locations","上海").put("department","研发")
                .put("description","开发服务").put("requirement",word+" Java Spring SQL").put("deadline","2099-12-31")
                .put("url","").put("source",sourceName).put("updatedAt","2026-10-20");
        jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload,user_id) VALUES (?,?,?,'intern',?::jsonb,?)",id,privateJob?"imported":"vertical",id,payload.toString(),privateJob?owner:null);
        return id;
    }
    @Test void localRecallMergesMcpMetadataKeepsDeadlineAndOwnerIsolation() throws Exception {
        String token=guests.create().accessToken(),other=guests.create().accessToken(),owner=guests.require("Bearer "+token);
        String word="unique"+UUID.randomUUID().toString().replace("-","");
        String a=seed(owner,word,"company-career-site",false),b=seed(owner,word,"USER_IMPORT",true);
        var raw=JSON.createObjectNode().put("id",a).put("site","vertical").put("title",word+"后端开发").put("nature","intern").put("company","测试企业").put("deadline","2099-12-31");
        var remote=JSON.createObjectNode(); remote.putArray("jobs").add(raw).add(raw);
        remote.putObject("_meta").putArray("sites").add("vertical"); when(source.search(anyMap())).thenReturn(remote);
        var found=call("GET","/jobs?query="+word+"&nature=intern",token,null);
        assertThat(found.path("items")).hasSize(2); assertThat(found.toString()).contains("LOCAL_DB","JOBS_MCP",b);
        assertThat(call("GET","/jobs/"+a,token,null).path("deadline").asText()).isEqualTo("2099-12-31");
        assertThat(call("GET","/jobs?query="+word+"&nature=intern",other,null).toString()).doesNotContain(b);
        // Absence is represented as empty/absent, never a fabricated date or URL.
        assertThat(call("GET","/jobs/"+b,token,null).path("url").asText()).isEmpty();
    }
    @Test void vectorAndKeywordFusionUsesMatchingFingerprintAndCurrentPayloadHash() throws Exception {
        String token=guests.create().accessToken(),owner=guests.require("Bearer "+token);
        String id=seed(owner,"semantic-only","company-career-site",true);
        String vector="[1"+",0".repeat(1023)+"]";
        when(embeddings.enabled()).thenReturn(true); when(embeddings.key()).thenReturn("stub-bge-m3"); when(embeddings.embed(anyString())).thenReturn(vector);
        jdbc.update("UPDATE jobs SET search_embedding=?::vector,embedding_key='stub-bge-m3',embedding_hash=md5(payload::text) WHERE id=?",vector,id);
        var criteria=new JobSearchParser.Criteria("no-lexical-hit","","","all","","rule");
        assertThat(local.search(owner,criteria).items()).extracting(j->j.path("id").asText()).contains(id);
        assertThat(local.search(guests.require("Bearer "+guests.create().accessToken()),criteria).items()).extracting(j->j.path("id").asText()).doesNotContain(id);
        jdbc.update("UPDATE jobs SET payload=jsonb_set(payload,'{description}','\"changed\"') WHERE id=?",id);
        assertThat(local.search(owner,criteria).items()).extracting(j->j.path("id").asText()).doesNotContain(id);
    }
    @Test void dailyCacheUsesAllSignalsRefreshesNextDayAndBoardCreationIsConcurrentIdempotent() throws Exception {
        String token=guests.create().accessToken(),owner=guests.require("Bearer "+token),other=guests.create().accessToken();
        String a=seed(owner,"Java","company-career-site",false);
        seed(owner,"Java","LOCAL_SNAPSHOT",false); seed(owner,"Java","demo",false);
        jdbc.update("INSERT INTO career_profiles(user_id,target,city,skills) VALUES (?,'后端开发','上海','SQL')",owner);
        String key=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES (?,?,'RESUME','test',?,'test.txt','text/plain',1,'fixture')",key,owner,key);
        jdbc.update("INSERT INTO resumes(id,user_id,title,source_file_id,parse_status) VALUES (?,?,'测试简历',?,'READY')",key,owner,key);
        jdbc.update("INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text) VALUES (?,?,?,1,'UPLOAD','{\"skills\":[\"Spring\"]}','Spring')",key,key,owner);
        jdbc.update("UPDATE resumes SET current_version_id=? WHERE id=?",key,key);
        var feed=call("GET","/jobs/recommendations",token,null);
        assertThat(feed.path("cached").asBoolean()).isFalse(); assertThat(feed.path("items").toString()).contains(a,"目标职业","画像技能/专业","保存简历","上海").doesNotContain("LOCAL_SNAPSHOT","demo");
        assertThat(call("GET","/jobs/recommendations",token,null).path("cached").asBoolean()).isTrue();
        assertThat(call("GET","/jobs/recommendations",other,null).path("items")).isEmpty();
        jdbc.update("UPDATE job_daily_recommendations SET day=day-1 WHERE user_id=?",owner);
        assertThat(call("GET","/jobs/recommendations",token,null).path("cached").asBoolean()).isFalse();
        var first=CompletableFuture.supplyAsync(()->call("POST","/applications",token,Map.of("jobId",a)));
        var second=CompletableFuture.supplyAsync(()->call("POST","/applications",token,Map.of("jobId",a)));
        assertThat(first.get(10,TimeUnit.SECONDS).path("id")).isEqualTo(second.get(10,TimeUnit.SECONDS).path("id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM applications WHERE user_id=? AND job_id=?",Integer.class,owner,a)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_events e JOIN applications a ON a.id=e.application_id WHERE a.user_id=? AND a.job_id=? AND e.kind='CREATED'",Integer.class,owner,a)).isEqualTo(1);
    }
}
