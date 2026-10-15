package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.application.ApprovalService;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.conversation.application.ConversationService;
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

import static org.assertj.core.api.Assertions.assertThat;

/** BD-02: agent writes (board and the other tools) survive a closed chat stream and is decided exactly once from the board. */
class BoardApprovalRecoveryIntegrationTest extends SharedHttpIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired GuestIdentityService guests;
    @Autowired ConversationService conversations;
    @Autowired ApprovalService approvals;

    HttpResponse<String> call(String token, String path, Object body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/agent/approvals" + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        b = body == null ? b.GET() : b.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).path("data");
    }

    /** Requests approval inside a run, then drops the subscriber as a reload or lost connection would. */
    String detachedApproval(String owner, String applicationId, String status) {
        var turn = conversations.begin(owner, null, "把这条投递改成面试中", AgentMode.AUTO, "fixture");
        var ticket = approvals.request(owner, turn.runId(), "updateApplicationStatus", Map.of("applicationId", applicationId, "status", status));
        ticket.await().subscribe().dispose();
        conversations.finish(turn, "", "CANCELLED", null, null); // what ChatService does when the client goes away
        return ticket.id();
    }

    @Test void pendingBoardWriteSurvivesStreamLossAndAppliesOnceFromBoard() throws Exception {
        String token = guests.create().accessToken(), owner = guests.require("Bearer " + token);
        String other = guests.create().accessToken();
        jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload) VALUES (?,'fixture',?,'campus','{\"company\":\"示例科技\",\"title\":\"后端实习生\"}')", owner + "job", owner);
        jdbc.update("INSERT INTO applications(id,user_id,job_id,note) VALUES (?,?,?,'')", owner + "app", owner, owner + "job");

        String id = detachedApproval(owner, owner + "app", "INTERVIEW");
        var pending = data(call(token, "/pending", null));
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).path("id").asText()).isEqualTo(id);
        assertThat(pending.get(0).path("company").asText()).isEqualTo("示例科技");
        assertThat(pending.get(0).path("currentStatus").asText()).isEqualTo("TODO");
        // Nothing is written before the decision, and another user can neither see nor decide it.
        assertThat(jdbc.queryForObject("SELECT status FROM applications WHERE id=?", String.class, owner + "app")).isEqualTo("TODO");
        assertThat(data(call(other, "/pending", null))).isEmpty();
        assertThat(call(other, "/" + id, Map.of("decision", "APPROVE")).statusCode()).isEqualTo(404);
        // Only editable fields may change.
        assertThat(call(token, "/" + id, Map.of("decision", "APPROVE", "args", Map.of("applicationId", "x"))).statusCode()).isEqualTo(400);

        var result = data(call(token, "/" + id, Map.of("decision", "APPROVE", "args", Map.of("note", "周五一面"))));
        assertThat(result.path("applied").asBoolean()).isTrue();
        assertThat(result.path("application").path("status").asText()).isEqualTo("INTERVIEW");
        assertThat(jdbc.queryForMap("SELECT status,note FROM applications WHERE id=?", owner + "app"))
                .containsEntry("status", "INTERVIEW").containsEntry("note", "周五一面");
        assertThat(call(token, "/" + id, Map.of("decision", "APPROVE")).statusCode()).isEqualTo(409);
        assertThat(data(call(token, "/pending", null))).isEmpty();

        // Rejecting writes nothing; an explicit stop withdraws the run's pending write.
        String rejected = detachedApproval(owner, owner + "app", "OFFER");
        assertThat(data(call(token, "/" + rejected, Map.of("decision", "REJECT"))).path("applied").asBoolean()).isFalse();
        var turn = conversations.begin(owner, null, "改成已结束", AgentMode.AUTO, "fixture");
        var ticket = approvals.request(owner, turn.runId(), "updateApplicationStatus", Map.of("applicationId", owner + "app", "status", "CLOSED"));
        conversations.cancel(owner, turn.runId(), null, List.of());
        ticket.await().subscribe().dispose();
        assertThat(data(call(token, "/pending", null))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM applications WHERE id=?", String.class, owner + "app")).isEqualTo("INTERVIEW");
    }

    /** The same recovery covers the other write tools: memory (still grounded on the run's own message) and interviews. */
    @Test void otherAgentWritesSurviveStreamLossAndApplyFromWorkbench() throws Exception {
        String token = guests.create().accessToken(), owner = guests.require("Bearer " + token);
        String content = "记住：我只考虑杭州的岗位";
        var candidate = com.offerpilot.memory.application.MemoryCandidate.extract(content);
        var turn = conversations.begin(owner, null, content, AgentMode.AUTO, "fixture");
        var memory = approvals.request(owner, turn.runId(), "saveUserFact",
                Map.of("kind", candidate.kind().name(), "fact", candidate.fact(), "source", candidate.source().name()));
        memory.await().subscribe().dispose();
        conversations.finish(turn, "", "CANCELLED", null, null);
        var interviewTurn = conversations.begin(owner, null, "开始模拟面试", AgentMode.AUTO, "fixture");
        var interview = approvals.request(owner, interviewTurn.runId(), "startInterview", Map.of("role", "后端工程师", "kind", "项目与经历"));
        interview.await().subscribe().dispose();
        conversations.finish(interviewTurn, "", "CANCELLED", null, null);

        var pending = data(call(token, "/pending", null));
        assertThat(pending.findValuesAsText("summary")).containsExactlyInAnyOrder(
                "记住：「" + candidate.fact() + "」", "开始一场「后端工程师」项目与经历模拟面试");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_memories WHERE user_id=?", Integer.class, owner)).isZero();
        // Non-board approvals take no edits.
        assertThat(call(token, "/" + memory.id(), Map.of("decision", "APPROVE", "args", Map.of("fact", "改写"))).statusCode()).isEqualTo(400);

        assertThat(data(call(token, "/" + memory.id(), Map.of("decision", "APPROVE"))).path("summary").asText()).contains("记忆已保存");
        assertThat(jdbc.queryForObject("SELECT fact FROM user_memories WHERE user_id=?", String.class, owner)).isEqualTo(candidate.fact());
        assertThat(data(call(token, "/" + interview.id(), Map.of("decision", "APPROVE"))).path("summary").asText()).contains("面试已创建");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_sessions WHERE user_id=?", Integer.class, owner)).isEqualTo(1);
        assertThat(call(token, "/" + interview.id(), Map.of("decision", "APPROVE")).statusCode()).isEqualTo(409);
        assertThat(data(call(token, "/pending", null))).isEmpty();
    }
}
