package com.offerpilot;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.auth.application.GuestIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** JB-04: disposable PostgreSQL/Flyway plus authenticated HTTP, no supplier calls. */
class BoardConcurrencyIntegrationTest extends SharedHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired GuestIdentityService guests;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json=new ObjectMapper();
    @Test void eightSimultaneousAddsPerUserConvergeAndUsersRemainIndependent() throws Exception {
        String job="jb04_"+UUID.randomUUID();
        jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload) VALUES (?,'jb04',?,'intern',?::jsonb)",job,job,
                json.writeValueAsString(Map.of("id",job,"title","合成测试岗位","company","合成夹具","site","jb04","nature","intern")));
        var tokens=List.of(guests.create().accessToken(),guests.create().accessToken());
        var owners=tokens.stream().map(t -> guests.require("Bearer "+t)).toList();
        var ready=new CountDownLatch(16); var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(16);
        var client=HttpClient.newHttpClient();
        try {
            var futures=new ArrayList<Future<JsonNode>>();
            for(var token:tokens) for(int i=0;i<8;i++) futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();
                return add(client,token,job);
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            var ids=new ArrayList<String>();
            for(int user=0;user<2;user++) {
                var results=new ArrayList<JsonNode>();
                for(int i=0;i<8;i++) results.add(futures.get(user*8+i).get(30,TimeUnit.SECONDS));
                var distinct=results.stream().map(r -> r.path("id").asText()).distinct().toList();
                assertThat(distinct).hasSize(1); assertThat(distinct.get(0)).isNotBlank(); ids.add(distinct.get(0));
                assertThat(results).allSatisfy(r -> {
                    assertThat(r.path("jobId").asText()).isEqualTo(job);
                    assertThat(r.path("status").asText()).isEqualTo("TODO");
                });
                assertThat(add(client,tokens.get(user),job).path("id").asText()).isEqualTo(ids.get(user));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM applications WHERE user_id=? AND job_id=?",Integer.class,owners.get(user),job)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM application_events WHERE application_id=? AND kind='CREATED'",Integer.class,ids.get(user))).isEqualTo(1);
            }
            assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
            jdbc.update("UPDATE applications SET note='仅用户A' WHERE id=?",ids.get(0));
            assertThat(jdbc.queryForObject("SELECT note FROM applications WHERE id=?",String.class,ids.get(1))).isEmpty();
        } finally {
            start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();
            for(var owner:owners) jdbc.update("DELETE FROM users WHERE id=?",owner);
            jdbc.update("DELETE FROM jobs WHERE id=?",job);
        }
    }
    private JsonNode add(HttpClient client,String token,String job) throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/applications"))
                .timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("jobId",job)))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }
}
