package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.auth.application.PasswordLoginService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class InterviewHttpIntegrationTest extends SharedHttpIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @LocalServerPort int port;
    @Autowired GuestIdentityService guests;
    @Autowired PasswordLoginService logins;
    @Autowired JdbcTemplate jdbc;


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
    void followUpIsAskedFromTheSavedAnswerAnsweredAndScoredTogether() throws Exception {
        String token = guests.create().accessToken();
        String id = create(token);
        // No answer yet: the interviewer has nothing to follow up on.
        assertThat(request("POST", "/" + id + "/answers/1/follow-up", token, null).statusCode()).isEqualTo(400);
        data(request("PUT", "/" + id + "/answers/1", token, Map.of("answerText", "我负责课程项目的后端接口。我设计了缓存。")));
        var asked = data(request("POST", "/" + id + "/answers/1/follow-up", token, null)).path("answers").get(1);
        // Offline test context has no model key, so the deterministic rule asks for the missing result.
        assertThat(asked.path("followUpSource").asText()).isEqualTo("rule");
        assertThat(asked.path("followUpQuestion").asText()).contains("我负责课程项目的后端接口").contains("结果");
        // Idempotent: asking again returns the same question.
        assertThat(data(request("POST", "/" + id + "/answers/1/follow-up", token, null)).path("answers").get(1)
                .path("followUpQuestion").asText()).isEqualTo(asked.path("followUpQuestion").asText());
        assertThat(request("POST", "/" + id + "/answers/1/follow-up", guests.create().accessToken(), null).statusCode()).isEqualTo(404);
        var answered = data(request("PUT", "/" + id + "/answers/1/follow-up", token, Map.of("answerText", "最终接口响应时间降低了 40%，用压测验证。")));
        assertThat(answered.path("answers").get(1).path("followUpAnswer").asText()).startsWith("最终接口");
        // Rewriting an answered main answer keeps the answered follow-up; unanswered ones are reset.
        data(request("PUT", "/" + id + "/answers/1", token, Map.of("answerText", "我负责课程项目的后端接口，我设计了缓存。")));
        assertThat(data(request("GET", "/" + id, token, null)).path("answers").get(1).path("followUpQuestion").asText()).isNotBlank();
        data(request("PUT", "/" + id + "/answers/2", token, Map.of("answerText", "我先排查了日志。")));
        data(request("POST", "/" + id + "/answers/2/follow-up", token, null));
        var reset = data(request("PUT", "/" + id + "/answers/2", token, Map.of("answerText", "我先排查了日志，再复现问题。")));
        assertThat(reset.path("answers").get(2).path("followUpQuestion").isNull()).isTrue();
        assertThat(request("PUT", "/" + id + "/answers/3/follow-up", token, Map.of("answerText", "x")).statusCode()).isEqualTo(400);
        // The follow-up answer counts toward STAR evidence and is exported in the recap.
        var recap = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/interviews/" + id + "/recap.md")).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(recap.body()).contains("面试官追问（规则）").contains("用压测验证");
    }

    @Test
    void createListDetailAnswerProgressAndCompletionPersistForGuest() throws Exception {
        String token = guests.create().accessToken();
        String jobId = "job_interview_" + UUID.randomUUID();
        jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload) VALUES (?,'fixture',?,'campus',?::jsonb)",
                jobId, jobId, "{\"title\":\"Java 工程师\",\"company\":\"示例公司\"}");
        var created = data(request("POST", "", token, Map.of("role", "  Java 工程师  ", "kind", "项目与经历", "jobId", jobId)));
        String id = created.path("id").asText();
        var list = data(request("GET", "", token, null));
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("role").asText()).isEqualTo("Java 工程师");
        var session = data(request("GET", "/" + id, token, null));
        for (var view : java.util.List.of(created, list.get(0), session))
            assertThat(view.path("jobId").asText()).isEqualTo(jobId);
        // Association survives subsequent answer/progress writes and remains private.
        assertThat(request("GET", "/" + id, guests.create().accessToken(), null).statusCode()).isEqualTo(404);
        assertThat(session.path("answers").size()).isEqualTo(5);
        for (int index = 0; index < 5; index++) {
            assertThat(session.path("answers").get(index).path("questionIndex").asInt()).isEqualTo(index);
            assertThat(session.path("answers").get(index).path("questionText").asText())
                    .isEqualTo(com.offerpilot.interview.application.InterviewService.QUESTIONS.get(index));
            assertThat(session.path("answers").get(index).path("answerText").asText()).isEmpty();
            assertThat(data(request("PUT", "/" + id + "/answers/" + index, token, Map.of("answerText", "  回答 " + index + "  "))).path("jobId").asText()).isEqualTo(jobId);
            assertThat(data(request("PATCH", "/" + id, token, Map.of("questionIndex", index))).path("jobId").asText()).isEqualTo(jobId);
        }
        data(request("PUT", "/" + id + "/answers/0", token, Map.of("answerText", "替换")));
        data(request("PUT", "/" + id + "/answers/1", token, Map.of("answerText", "")));
        assertThat(data(request("PATCH", "/" + id, token, Map.of("completed", true))).path("jobId").asText()).isEqualTo(jobId);
        session = data(request("GET", "/" + id, token, null));
        assertThat(session.path("completed").asBoolean()).isTrue();
        assertThat(session.path("questionIndex").asInt()).isEqualTo(4);
        assertThat(session.path("answers").get(0).path("answerText").asText()).isEqualTo("替换");
        assertThat(session.path("answers").get(1).path("answerText").asText()).isEmpty();
        assertThat(session.path("answers").get(4).path("answerText").asText()).isEqualTo("回答 4");
        assertThat(data(request("GET", "", token, null)).get(0).path("completed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_answers WHERE session_id=?", Integer.class, id)).isEqualTo(5);
        var recap = request("GET", "/" + id + "/recap.md", token, null);
        assertThat(recap.statusCode()).withFailMessage(recap.body()).isEqualTo(200);
        assertThat(recap.headers().firstValue("Content-Type").orElse("")).contains("text/markdown");
        assertThat(recap.body()).contains("替换", "回答 4", "未作答", "文字规则评分");

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
        assertThat(original.path("jobId").isNull()).isTrue();
        assertThat(data(request("GET", "", account, null)).get(0).path("jobId").isNull()).isTrue();
        assertThat(original.path("completed").asBoolean()).isFalse();
        assertThat(original.path("answers").get(0).path("answerText").asText()).isEmpty();
        assertThat(request("GET", "", null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/" + id + "/recap.md", guest, null).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/" + id + "/recap.md", null, null).statusCode()).isEqualTo(401);

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
