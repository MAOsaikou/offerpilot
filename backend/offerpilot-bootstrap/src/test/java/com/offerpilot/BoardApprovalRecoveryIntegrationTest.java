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

/** BD-02: an agent board write survives a closed chat stream and is decided exactly once from the board. */
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
}
