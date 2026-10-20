package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.GuestIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CareerRerankHttpIntegrationTest extends SharedHttpIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    @LocalServerPort int port;
    @Autowired GuestIdentityService identities;
    @Autowired JdbcTemplate jdbc;
    HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/career" + path))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).path("data");
    }
    String setup(String token) throws Exception {
        data(request("PUT", "/profile", token, Map.of("target", "后端开发工程师", "city", "上海", "skills", "Java SQL",
                "interests", List.of("技术研发", "数据分析"))));
        return data(request("POST", "/recommendations", token, null)).path("id").asText();
    }
    @Test void revisionsArePrivateImmutableLinkedAndMeasuredWithRealSeedData() throws Exception {
        String token = identities.create().accessToken(), other = identities.create().accessToken();
        assertThat(request("GET", "/recommendations", null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/occupations", null, null).statusCode()).isEqualTo(401);
        assertThat(data(request("GET", "/recommendations", token, null)).isNull()).isTrue();
        String parentId = setup(token);
        var parent = data(request("GET", "/recommendations/" + parentId, token, null));
        assertThat(parent.path("items").size()).isEqualTo(8);
        var neutral = data(request("POST", "/recommendations/" + parentId + "/rerank", token,
                Map.of("city", "杭州", "avoidOvertime", true, "research", false)));
        assertThat(neutral.path("items").findValues("rankDelta")).allSatisfy(delta -> assertThat(delta.asInt()).isZero());
        var constraints = Map.of("city", "杭州", "minSalaryK", 30, "avoidOvertime", true, "research", true);
        assertThat(request("GET", "/recommendations/" + parentId, other, null).statusCode()).isEqualTo(404);
        assertThat(request("POST", "/recommendations/" + parentId + "/rerank", other, constraints).statusCode()).isEqualTo(404);
        assertThat(request("POST", "/recommendations/" + parentId + "/rerank", null, constraints).statusCode()).isEqualTo(401);
        assertThat(request("POST", "/recommendations/" + parentId + "/rerank", token,
                Map.of("city", "", "minSalaryK", -1)).statusCode()).isEqualTo(400);
        for (Object invalid : List.of(30.5, "30", false)) {
            var bad = new HashMap<String, Object>(constraints);
            bad.put("minSalaryK", invalid);
            assertThat(request("POST", "/recommendations/" + parentId + "/rerank", token, bad).statusCode()).isEqualTo(400);
        }
        var otherOwner = identities.require("Bearer " + other);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO career_recommendation_revisions(id,user_id,parent_id,snapshot) VALUES (?,?,?,'{}')",
                UUID.randomUUID().toString(), otherOwner, parentId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        List<Long> times = new ArrayList<>();
        JsonNode first = null, latest = null;
        for (int n = 0; n < 20; n++) {
            long started = System.nanoTime();
            var saved = data(request("POST", "/recommendations/" + parentId + "/rerank", token, constraints));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            times.add(elapsedMs);
            assertThat(elapsedMs).isLessThan(8000);
            assertThat(saved.path("parentId").asText()).isEqualTo(parentId);
            if (first == null) first = saved;
            else assertThat(saved.path("items")).isEqualTo(first.path("items"));
            for (var item : saved.path("items")) {
                int previousRank = 0;
                for (var old : parent.path("items")) if (old.path("occupation").path("occupationId").equals(item.path("occupation").path("occupationId"))) previousRank = old.path("rank").asInt();
                assertThat(item.path("rankDelta").asInt()).isEqualTo(previousRank - item.path("rank").asInt());
                assertThat(item.path("changeReason").asText()).contains("研究", "30k", "杭州", "待核实");
            }
            latest = saved;
        }
        assertThat(data(request("GET", "/recommendations", token, null))).isEqualTo(latest);
        assertThat(data(request("GET", "/recommendations/" + parentId, token, null))).isEqualTo(parent);
        assertThat(data(request("GET", "/recommendations", other, null)).isNull()).isTrue();
        var sorted = times.stream().sorted().toList();
        String report = "# CD-06 local offline HTTP latency\n\n20 requests; real seeded catalog, PostgreSQL Testcontainers, no model/embedding calls.\n\n"
                + "p50=" + sorted.get(9) + " ms; p95=" + sorted.get(18) + " ms; max=" + sorted.get(19) + " ms.\n\n"
                + "Samples (ms): " + times + "\n\nNot a production load/network measurement. elapsedMs in payload excludes insert/commit/HTTP; these samples include them.\n";
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/career-rerank-latency.md"), report);
        String owner = identities.require("Bearer " + token);
        jdbc.update("DELETE FROM users WHERE id=?", owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM career_recommendation_revisions WHERE user_id=?", Integer.class, owner)).isZero();
    }
    @Test void comparisonUsesOnlyCatalogFactsAndCurrentOwnerProfileAndChecksSelectionBounds() throws Exception {
        String token = identities.create().accessToken(), other = identities.create().accessToken();
        setup(token);
        assertThat(data(request("GET", "/occupations", token, null)).size()).isGreaterThanOrEqualTo(300);
        String path = "/compare?ids=occ_backend_dev,occ_frontend_dev";
        var comparison = data(request("GET", path, token, null));
        assertThat(comparison.path("axes")).isEqualTo(JSON.valueToTree(List.of("R", "I", "A", "S", "E", "C")));
        var backend = comparison.path("occupations").get(0);
        assertThat(backend.path("riasec").path("I").asDouble()).isEqualTo(.84);
        assertThat(backend.path("salaryRange").isNull() || backend.path("salaryRange").isMissingNode()).isTrue(); // KB-01: no trusted salary source
        assertThat(backend.path("match").isNumber()).isTrue();
        assertThat(data(request("GET", path, other, null)).path("occupations").get(0).path("match").isNull()).isTrue();
        for (String ids : List.of("occ_backend_dev", "occ_backend_dev,occ_backend_dev", "occ_backend_dev,missing",
                "occ_backend_dev,occ_frontend_dev,occ_data_analyst,occ_mobile_dev"))
            assertThat(request("GET", "/compare?ids=" + ids, token, null).statusCode()).isEqualTo(400);
        assertThat(data(request("GET", path + ",occ_data_analyst", token, null)).path("occupations").size()).isEqualTo(3);
    }
}
