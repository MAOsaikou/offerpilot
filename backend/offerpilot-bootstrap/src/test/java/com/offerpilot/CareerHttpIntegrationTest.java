package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.career.application.GrowthTaskCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class CareerHttpIntegrationTest extends SharedHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @LocalServerPort int port;
    @Autowired GuestIdentityService identities;
    @Autowired JdbcTemplate jdbc;


    private HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).path("data");
    }

    private void invalid(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).path("code").asInt()).isEqualTo(10001);
    }

    private void invalidJsonType(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).path("code").asInt()).isEqualTo(10004);
    }

    private Map<String, Object> profile(String target) {
        return Map.of("target", target, "city", "上海", "skills", "Java、SQL\n项目实践", "interests", List.of("技术研发"));
    }

    @Test
    void emptyProfileAndEveryEndpointRequireIdentity() throws Exception {
        assertThat(request("GET", "/career/profile", null, null).statusCode()).isEqualTo(401);
        assertThat(request("PUT", "/career/profile", null, profile("开发工程师")).statusCode()).isEqualTo(401);
        assertThat(request("PUT", "/career/tasks", null, Map.of("completed", List.of())).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/career/profile", "invalid", null).statusCode()).isEqualTo(401);
        String token = identities.create().accessToken();
        var empty = data(request("GET", "/career/profile", token, null));
        assertThat(empty.path("target").asText()).isEmpty();
        assertThat(empty.path("city").asText()).isEmpty();
        assertThat(empty.path("skills").asText()).isEmpty();
        assertThat(empty.path("interests")).isEmpty();
        assertThat(empty.path("completedTasks")).isEmpty();
        assertThat(empty.path("updatedAt").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM career_profiles WHERE user_id=?", Integer.class,
                identities.require("Bearer " + token))).isZero();
    }

    @Test
    void profilesPersistForGuestsAndRegisteredUsersAndStayPrivate() throws Exception {
        String guest = identities.create().accessToken();
        String registered = data(request("POST", "/auth/register", null, Map.of(
                "email", UUID.randomUUID() + "@example.test", "password", "PrivateTest123!", "nickname", "Career test")))
                .path("accessToken").asText();
        assertThat(registered).isNotBlank();
        for (String token : List.of(guest, registered)) {
            String target = token.equals(guest) ? "开发工程师" : "数据分析师";
            var saved = data(request("PUT", "/career/profile", token, profile(target)));
            assertThat(saved.path("target").asText()).isEqualTo(target);
            assertThat(saved.path("city").asText()).isEqualTo("上海");
            assertThat(saved.path("skills").asText()).isEqualTo("Java、SQL\n项目实践");
            assertThat(saved.path("interests").get(0).asText()).isEqualTo("技术研发");
            assertThat(saved.path("updatedAt").isTextual()).isTrue();
            assertThat(data(request("GET", "/career/profile", token, null))).isEqualTo(saved);
        }
        assertThat(data(request("GET", "/career/profile", guest, null)).path("target").asText()).isEqualTo("开发工程师");
        data(request("PUT", "/career/profile", registered, profile("产品经理")));
        assertThat(data(request("GET", "/career/profile", guest, null)).path("target").asText()).isEqualTo("开发工程师");
        assertThat(data(request("GET", "/career/profile", identities.create().accessToken(), null)).path("target").asText()).isEmpty();
    }

    @Test
    void catalogChecklistReplacesDeduplicatesAndPreservesOtherUsersAndProfile() throws Exception {
        String token = identities.create().accessToken(), other = identities.create().accessToken();
        String first = GrowthTaskCatalog.TITLES.get(0), second = GrowthTaskCatalog.TITLES.get(1);
        // Tasks can be saved before a profile exists; V17 was applied to this fresh database.
        var saved = data(request("PUT", "/career/tasks", token, Map.of("completed", List.of(second, first, first))));
        assertThat(saved.path("completedTasks")).isEqualTo(JSON.valueToTree(List.of(first, second)));
        assertThat(data(request("GET", "/career/profile", other, null)).path("completedTasks")).isEmpty();
        String owner = identities.require("Bearer " + token);
        var completedAt = jdbc.queryForObject("SELECT completed_at FROM growth_task_completions WHERE user_id=? AND task_title=?",
                java.sql.Timestamp.class, owner, first);
        invalid(request("PUT", "/career/tasks", token, Map.of("completed", List.of(first, "未知任务"))));
        assertThat(data(request("GET", "/career/profile", token, null))).isEqualTo(saved);
        data(request("PUT", "/career/tasks", other, Map.of("completed", List.of(second))));
        data(request("PUT", "/career/profile", token, profile("前端开发")));
        var replaced = data(request("PUT", "/career/tasks", token, Map.of("completed", List.of(first))));
        assertThat(replaced.path("target").asText()).isEqualTo("前端开发");
        assertThat(replaced.path("completedTasks")).isEqualTo(JSON.valueToTree(List.of(first)));
        assertThat(data(request("GET", "/career/profile", token, null))).isEqualTo(replaced);
        assertThat(jdbc.queryForObject("SELECT completed_at FROM growth_task_completions WHERE user_id=? AND task_title=?",
                java.sql.Timestamp.class, owner, first)).isEqualTo(completedAt);
        assertThat(data(request("GET", "/career/profile", other, null)).path("completedTasks"))
                .isEqualTo(JSON.valueToTree(List.of(second)));
        assertThat(data(request("PUT", "/career/tasks", token, Map.of("completed", List.of()))).path("completedTasks")).isEmpty();
    }

    @Test
    void validatesAllProfileBoundsAndTaskInputsWithoutChangingSavedData() throws Exception {
        String token = identities.create().accessToken();
        var saved = data(request("PUT", "/career/profile", token, profile("原目标")));
        invalid(request("PUT", "/career/profile", token, profile("长".repeat(101))));
        for (var entry : Map.of("city", "城".repeat(101), "skills", "技".repeat(3001),
                "interests", List.of("兴".repeat(51))).entrySet()) {
            var body = new java.util.HashMap<>(profile("原目标"));
            body.put(entry.getKey(), entry.getValue());
            invalid(request("PUT", "/career/profile", token, body));
        }
        var body = new java.util.HashMap<>(profile("原目标"));
        body.put("interests", java.util.Collections.nCopies(21, "技术研发"));
        invalid(request("PUT", "/career/profile", token, body));
        body.put("interests", java.util.Arrays.asList((String) null));
        invalid(request("PUT", "/career/profile", token, body));
        body.put("interests", List.of(1));
        invalidJsonType(request("PUT", "/career/profile", token, body));
        body.put("interests", List.of("技术研发"));
        body.put("target", 123);
        invalidJsonType(request("PUT", "/career/profile", token, body));
        invalid(request("PUT", "/career/profile", token, Map.of()));
        invalid(request("PUT", "/career/tasks", token, Map.of()));
        invalid(request("PUT", "/career/tasks", token, Map.of("completed", java.util.Arrays.asList((String) null))));
        assertThat(data(request("GET", "/career/profile", token, null))).isEqualTo(saved);
        // Supplementary Unicode characters have the same limits at HTTP and SQL layers.
        assertThat(data(request("PUT", "/career/profile", token, profile("😀".repeat(100)))).path("target").asText())
                .isEqualTo("😀".repeat(100));
        invalid(request("PUT", "/career/profile", token, profile("😀".repeat(101))));
    }

    @Test
    void databaseConstraintsTimestampAndUserDeletionProtectPersistedData() throws Exception {
        String token = identities.create().accessToken(), owner = identities.require("Bearer " + token);
        data(request("PUT", "/career/profile", token, profile("原目标")));
        data(request("PUT", "/career/tasks", token, Map.of("completed", GrowthTaskCatalog.TITLES)));
        for (String invalidJson : List.of("{}", "[1]", "[null]", JSON.writeValueAsString(List.of("长".repeat(51))),
                JSON.writeValueAsString(java.util.Collections.nCopies(21, "技术研发")))) {
            assertThatThrownBy(() -> jdbc.update("UPDATE career_profiles SET interests=?::jsonb WHERE user_id=?", invalidJson, owner))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        for (var entry : Map.of("target", 101, "city", 101, "skills", 3001, "major", 101, "courses", 3001, "values_text", 1001).entrySet()) {
            assertThatThrownBy(() -> jdbc.update("UPDATE career_profiles SET " + entry.getKey() + "=? WHERE user_id=?",
                    "长".repeat(entry.getValue()), owner)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        jdbc.update("UPDATE career_profiles SET updated_at='2000-01-01' WHERE user_id=?", owner);
        assertThat(jdbc.queryForObject("SELECT updated_at > '2020-01-01'::timestamptz FROM career_profiles WHERE user_id=?",
                Boolean.class, owner)).isTrue();
        jdbc.update("DELETE FROM users WHERE id=?", owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM career_profiles WHERE user_id=?", Integer.class, owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_task_completions WHERE user_id=?", Integer.class, owner)).isZero();
    }
}
