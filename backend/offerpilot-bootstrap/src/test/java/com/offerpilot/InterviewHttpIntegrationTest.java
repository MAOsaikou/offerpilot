package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.auth.application.PasswordLoginService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none", "spring.ai.mcp.server.enabled=false",
        "offerpilot.resume.worker-enabled=false"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class InterviewHttpIntegrationTest {
    @Container
    static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    static final ObjectMapper JSON = new ObjectMapper();
    @LocalServerPort int port;
    @Autowired GuestIdentityService guests;
    @Autowired PasswordLoginService logins;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.username", database::getUsername);
        properties.add("spring.datasource.password", database::getPassword);
    }

    HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/interviews" + path))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).path("data");
    }

    String create(String token) throws Exception {
        return data(request("POST", "", token, Map.of("role", "  Java 工程师  ", "kind", "项目与经历"))).path("id").asText();
    }

    @Test
    void createListDetailAnswerProgressAndCompletionPersistForGuest() throws Exception {
        String token = guests.create().accessToken();
        String id = create(token);
        var list = data(request("GET", "", token, null));
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("role").asText()).isEqualTo("Java 工程师");
        var session = data(request("GET", "/" + id, token, null));
        assertThat(session.path("answers").size()).isEqualTo(5);
        for (int index = 0; index < 5; index++) {
            assertThat(session.path("answers").get(index).path("questionIndex").asInt()).isEqualTo(index);
            assertThat(session.path("answers").get(index).path("questionText").asText())
                    .isEqualTo(com.offerpilot.interview.application.InterviewService.QUESTIONS.get(index));
            assertThat(session.path("answers").get(index).path("answerText").asText()).isEmpty();
            data(request("PUT", "/" + id + "/answers/" + index, token, Map.of("answerText", "  回答 " + index + "  ")));
            data(request("PATCH", "/" + id, token, Map.of("questionIndex", index)));
        }
        data(request("PUT", "/" + id + "/answers/0", token, Map.of("answerText", "替换")));
        data(request("PUT", "/" + id + "/answers/1", token, Map.of("answerText", "")));
        data(request("PATCH", "/" + id, token, Map.of("completed", true)));
        session = data(request("GET", "/" + id, token, null));
        assertThat(session.path("completed").asBoolean()).isTrue();
        assertThat(session.path("questionIndex").asInt()).isEqualTo(4);
        assertThat(session.path("answers").get(0).path("answerText").asText()).isEqualTo("替换");
        assertThat(session.path("answers").get(1).path("answerText").asText()).isEmpty();
        assertThat(session.path("answers").get(4).path("answerText").asText()).isEqualTo("回答 4");
        assertThat(data(request("GET", "", token, null)).get(0).path("completed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_answers WHERE session_id=?", Integer.class, id)).isEqualTo(5);
    }

    @Test
    void registeredAndGuestOwnersCannotReadOrMutateEachOthersSessions() throws Exception {
        String guest = guests.create().accessToken();
        String account = logins.register("interview-" + UUID.randomUUID() + "@example.test", "fixture-password").accessToken();
        String id = create(account);
        assertThat(data(request("GET", "", guest, null)).size()).isZero();
        for (String target : new String[] {id, "missing"}) {
            for (var response : java.util.List.of(
                    request("GET", "/" + target, guest, null),
                    request("PATCH", "/" + target, guest, Map.of("completed", true)),
                    request("PUT", "/" + target + "/answers/0", guest, Map.of("answerText", "越权")))) {
                assertThat(response.statusCode()).isEqualTo(404);
                assertThat(JSON.readTree(response.body()).path("code").asInt()).isEqualTo(30701);
            }
        }
        var original = data(request("GET", "/" + id, account, null));
        assertThat(original.path("completed").asBoolean()).isFalse();
        assertThat(original.path("answers").get(0).path("answerText").asText()).isEmpty();
        assertThat(request("GET", "", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void postgresUnicodeBoundariesQuestionSnapshotAndOwnerCascade() throws Exception {
        String token = guests.create().accessToken();
        String id = data(request("POST", "", token, Map.of("role", "😀".repeat(100), "kind", "职业动机"))).path("id").asText();
        data(request("PUT", "/" + id + "/answers/0", token, Map.of("answerText", "😀".repeat(12000))));
        assertThat(request("PUT", "/" + id + "/answers/0", token, Map.of("answerText", "😀".repeat(12001))).statusCode()).isEqualTo(400);
        assertThat(request("POST", "", token, Map.of("role", "a".repeat(101), "kind", "职业动机")).statusCode()).isEqualTo(400);
        assertThat(request("POST", "", token, Map.of("role", "工程师", "kind", "其他")).statusCode()).isEqualTo(400);
        assertThat(request("PATCH", "/" + id, token, Map.of("questionIndex", 5)).statusCode()).isEqualTo(400);
        assertThat(request("PUT", "/" + id + "/answers/-1", token, Map.of("answerText", "")).statusCode()).isEqualTo(400);
        jdbc.update("UPDATE interview_answers SET question_text='已存储的快照' WHERE session_id=? AND question_index=0", id);
        assertThat(data(request("GET", "/" + id, token, null)).path("answers").get(0).path("questionText").asText()).isEqualTo("已存储的快照");
        String owner = guests.require("Bearer " + token);
        assertThatThrownBy(() -> jdbc.update("UPDATE interview_answers SET answer_text=? WHERE session_id=? AND question_index=0", "a".repeat(12001), id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM users WHERE id=?", owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_answers WHERE session_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_sessions WHERE id=?", Integer.class, id)).isZero();
    }
}
