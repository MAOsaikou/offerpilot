package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.llm.*;
import com.offerpilot.resume.application.ResumeObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import reactor.core.publisher.Flux;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL/V41/HTTP contract; only object storage and paid provider IO are stubbed. */
class JobScreenshotImportHttpIntegrationTest extends SharedHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired GuestIdentityService guests;
    @Autowired JdbcTemplate jdbc;
    @MockBean ResumeObjectStore storage;
    @MockBean ModelGateway gateway;
    static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    static final byte[] PNG=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2vGQAAAAASUVORK5CYII=");
    @BeforeEach void provider() {
        var objects=new java.util.concurrent.ConcurrentHashMap<String,byte[]>();
        when(storage.bucket()).thenReturn("private-fixture");
        doAnswer(c -> { objects.put(c.getArgument(0),c.getArgument(1)); return null; }).when(storage).put(anyString(),any(),anyString());
        when(storage.get(anyString(),anyString())).thenAnswer(c -> objects.get(c.getArgument(1)));
        when(gateway.detectInjection(anyString())).thenReturn(List.of());
        when(gateway.stream(eq(ModelTier.VISION_ALT),anyList(),eq(0.0))).thenReturn(Flux.just(new ChatResponse(
                List.of(new Generation(new AssistantMessage(JobImportHttpIntegrationTest.JD))))));
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.0))).thenReturn(Flux.error(new IllegalStateException("offline text extraction")));
    }
    HttpResponse<String> call(String method,String path,String token,Object body) throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Authorization","Bearer "+token).header("Content-Type","application/json");
        return HttpClient.newHttpClient().send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);
        return JSON.readTree(r.body()).path("data");
    }
    HttpResponse<String> upload(String token,String filename,byte[] bytes) throws Exception {
        var out=new java.io.ByteArrayOutputStream();
        out.write(("--fixture\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes); out.write("\r\n--fixture--\r\n".getBytes(StandardCharsets.UTF_8));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/jobs/import/screenshots"))
                .header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary=fixture")
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode start(String token,List<String> ids) throws Exception {
        return data(call("POST","/generation-tasks",token,Map.of("kind","JOB_IMPORT","entityId","current","requestId",UUID.randomUUID().toString(),"input",Map.of("fileIds",ids))));
    }
    JsonNode finished(String token,String id) throws Exception {
        for(int n=0;n<100;n++) {
            var t=data(call("GET","/generation-tasks/"+id,token,null));
            if(!Set.of("QUEUED","RUNNING").contains(t.path("status").asText())) return t;
            Thread.sleep(50);
        }
        throw new AssertionError("task did not finish");
    }
    @Test void privateScreenshotsProduceRecoverableReviewDraftWithoutCreatingJob() throws Exception {
        var token=guests.create().accessToken(); var other=guests.create().accessToken();
        var ids=new ArrayList<String>();
        for(int i=0;i<3;i++) ids.add(data(upload(token,"page-"+i+".png",PNG)).path("fileId").asText());
        assertThat(call("GET","/jobs/import/screenshots/"+ids.get(0),other,null).statusCode()).isEqualTo(404);
        assertThat(call("GET","/jobs/import/screenshots/"+ids.get(0),token,null).statusCode()).isEqualTo(200);
        assertThat(call("POST","/generation-tasks",other,Map.of("kind","JOB_IMPORT","entityId","current","requestId",UUID.randomUUID().toString(),"input",Map.of("fileIds",ids))).statusCode()).isEqualTo(404);
        int before=jdbc.queryForObject("SELECT count(*) FROM jobs",Integer.class);
        var task=start(token,ids); var id=task.path("id").asText();
        assertThat(finished(token,id).path("status").asText()).isEqualTo("SUCCEEDED");
        var result=data(call("GET","/generation-tasks/"+id+"/result",token,null));
        assertThat(result.path("sourceKind").asText()).isEqualTo("screenshot");
        assertThat(result.path("fileIds").size()).isEqualTo(3);
        assertThat(result.path("draft").path("company").asText()).isEqualTo("星河科技");
        assertThat(result.path("draft").path("requirement").asText()).contains("Spring Boot");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs",Integer.class)).isEqualTo(before);
        assertThat(call("GET","/generation-tasks/"+id+"/result",other,null).statusCode()).isEqualTo(404);
        assertThat(data(call("GET","/generation-tasks/"+id+"/result",token,null))).isEqualTo(result);
        var fields=(com.fasterxml.jackson.databind.node.ObjectNode)result.path("draft").deepCopy();
        fields.remove("parser"); fields.put("url",""); fields.put("title","已核对岗位");
        var saved=data(call("POST","/jobs/import",token,fields));
        assertThat(call("GET","/jobs/"+saved.path("id").asText(),other,null).statusCode()).isEqualTo(404);
    }
    @Test void invalidUploadsAndUnavailableVisionFailExplicitly() throws Exception {
        var token=guests.create().accessToken();
        assertThat(upload(token,"page.png",new byte[]{1}).statusCode()).isEqualTo(400);
        assertThat(upload(token,"page.png",new byte[5*1024*1024+1]).statusCode()).isEqualTo(413);
        var id=data(upload(token,"page.png",PNG)).path("fileId").asText();
        doThrow(new com.offerpilot.common.error.BusinessException(com.offerpilot.common.error.ErrorCode.MODEL_NOT_CONFIGURED)).when(gateway).requireAvailable(ModelTier.VISION_ALT,true);
        var task=start(token,List.of(id));
        var failed=finished(token,task.path("id").asText());
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("error").asText()).contains("粘贴");
        verify(gateway,never()).stream(eq(ModelTier.VISION_ALT),anyList(),anyDouble());
    }
}
