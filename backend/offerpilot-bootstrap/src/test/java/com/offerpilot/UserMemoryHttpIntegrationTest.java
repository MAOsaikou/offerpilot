package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.memory.application.UserMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.*;
import java.util.concurrent.Executors;
import static com.offerpilot.memory.application.UserMemoryService.*;
import static org.assertj.core.api.Assertions.*;

class UserMemoryHttpIntegrationTest extends SharedHttpIntegrationTest {
    @Autowired GuestIdentityService identities;
    @Autowired ObjectMapper json;
    @Autowired UserMemoryService memories;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/memories"+path))
                .header("Content-Type","application/json");
        if(token!=null) builder.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200); return json.readTree(response.body()).path("data");
    }
    String owner(String token) { return identities.require("Bearer "+token); }
    @Test void crudAuthenticationIsolationDedupeAndPreferences() throws Exception {
        String a=identities.create().accessToken(), b=identities.create().accessToken();
        assertThat(request("GET","",null,null).statusCode()).isEqualTo(401);
        assertThat(data(request("GET","",a,null)).path("memoryEnabled").asBoolean()).isTrue();
        String id=data(request("POST","",a,"{\"kind\":\"SKILL\",\"fact\":\"Java  Spring\"}")).path("id").asText();
        assertThat(data(request("POST","",a,"{\"kind\":\"OTHER\",\"fact\":\"ＪＡＶＡ spring。\"}")).path("id").asText()).isEqualTo(id);
        assertThat(data(request("GET","",a,null)).path("items")).hasSize(1);
        assertThat(data(request("GET","",b,null)).path("items")).isEmpty();
        assertThat(request("PATCH","/"+id,b,"{\"kind\":\"GOAL\",\"fact\":\"cross user\"}").statusCode()).isEqualTo(404);
        assertThat(request("DELETE","/"+id,b,null).statusCode()).isEqualTo(404);
        var edited=data(request("PATCH","/"+id,a,"{\"kind\":\"LOCATION\",\"fact\":\"我的目标城市是杭州\"}"));
        assertThat(edited.path("source").asText()).isEqualTo("MANUAL");
        assertThat(data(request("PUT","/settings",a,"{\"memoryEnabled\":false}")).path("memoryEnabled").asBoolean()).isFalse();
        assertThat(memories.recall(owner(a),"城市")).isEmpty();
        assertThat(data(request("GET","",a,null)).path("items")).hasSize(1); // OFF preserves facts for management.
        assertThat(request("PUT","/settings",a,"{}").statusCode()).isEqualTo(400);
        data(request("DELETE","/"+id,a,null));
        assertThat(request("DELETE","/"+id,a,null).statusCode()).isEqualTo(404);
        data(request("POST","",a,"{\"kind\":\"GOAL\",\"fact\":\"目标Java\"}"));
        data(request("DELETE","",b,null));
        assertThat(data(request("GET","",a,null)).path("items")).hasSize(1);
        data(request("DELETE","",a,null));
        assertThat(data(request("GET","",a,null)).path("items")).isEmpty();
    }
    @Test void capsUnicodeBoundariesAndEditCollision() throws Exception {
        String token=identities.create().accessToken(), owner=owner(token);
        String first=memories.add(owner,new Draft(Kind.OTHER,"😀".repeat(200))).id();
        assertThat(request("POST","",token,json.writeValueAsString(new Draft(Kind.OTHER,"😀".repeat(201)))).statusCode()).isEqualTo(400);
        assertThat(request("POST","",token,"{\"kind\":\"BAD\",\"fact\":\"x\"}").statusCode()).isEqualTo(400);
        assertThat(request("POST","",token,"{\"kind\":\"OTHER\",\"fact\":\"   \"}").statusCode()).isEqualTo(400);
        for(int i=1;i<50;i++) memories.add(owner,new Draft(Kind.OTHER,"事实"+i));
        assertThat(request("POST","",token,"{\"kind\":\"OTHER\",\"fact\":\"overflow\"}").statusCode()).isEqualTo(409);
        assertThat(data(request("POST","",token,json.writeValueAsString(new Draft(Kind.OTHER,"😀".repeat(200))))).path("id").asText()).isEqualTo(first);
        assertThat(request("PATCH","/"+first,token,"{\"kind\":\"OTHER\",\"fact\":\"事实1\"}").statusCode()).isEqualTo(409);
        assertThat(memories.list(owner).items()).hasSize(50);
    }
    @Test void concurrentAddsSerializeCapAndNormalizedDedupe() throws Exception {
        String owner=owner(identities.create().accessToken());
        var pool=Executors.newFixedThreadPool(4);
        try {
            var same=pool.invokeAll(java.util.stream.IntStream.range(0,8).<java.util.concurrent.Callable<Memory>>mapToObj(i -> () -> memories.add(owner,new Draft(Kind.SKILL,i%2==0?"Java":"ＪＡＶＡ"))).toList());
            var ids=new java.util.HashSet<String>(); for(var item:same) ids.add(item.get().id());
            assertThat(ids).hasSize(1);
            for(int i=1;i<49;i++) memories.add(owner,new Draft(Kind.OTHER,"fact"+i));
            var adds=pool.invokeAll(java.util.stream.IntStream.range(0,4).<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> {
                try { memories.add(owner,new Draft(Kind.OTHER,"last"+i)); return true; }
                catch(com.offerpilot.common.error.BusinessException ex) { assertThat(ex.errorCode()).isEqualTo(com.offerpilot.common.error.ErrorCode.MEMORY_CONFLICT); return false; }
            }).toList());
            int success=0; for(var item:adds) if(item.get()) success++;
            assertThat(success).isEqualTo(1); assertThat(memories.list(owner).items()).hasSize(50);
        } finally { pool.shutdownNow(); }
    }
    @Test void recallAlwaysIncludesLatestGoalLocationAndOnlyRelevantOtherFacts() {
        String a=owner(identities.create().accessToken()),b=owner(identities.create().accessToken());
        memories.add(a,new Draft(Kind.GOAL,"期望Java后端"));
        memories.add(a,new Draft(Kind.LOCATION,"目标杭州"));
        memories.add(a,new Draft(Kind.OTHER,"不相关的烘焙"));
        for(int i=0;i<8;i++) memories.add(a,new Draft(Kind.SKILL,"Java Spring 技能"+i));
        memories.add(b,new Draft(Kind.GOAL,"其他用户哨兵"));
        var recalled=memories.recall(a,"Java Spring");
        assertThat(recalled).hasSize(5);
        assertThat(recalled.stream().map(Memory::kind)).contains(Kind.GOAL,Kind.LOCATION);
        assertThat(recalled.stream().map(Memory::fact)).doesNotContain("不相关的烘焙","其他用户哨兵");
        assertThat(memories.recall(a,"量子物理")).hasSize(2);
        assertThat(memories.recall(a,"!!!")).hasSize(2);
        jdbc.update("DELETE FROM users WHERE id=?",a);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_memories WHERE user_id=?",Integer.class,a)).isZero();
        assertThat(memories.enabled(a)).isFalse();
        assertThatThrownBy(() -> memories.add(a,new Draft(Kind.GOAL,"迟到写入"))).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        assertThat(memories.list(b).items()).hasSize(1);
    }
}
