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
    @Autowired com.offerpilot.agent.application.ApprovalStore approvalStore;
    @Autowired com.offerpilot.agent.application.RunRelay relay;
    @Autowired com.offerpilot.agent.application.WorkbenchEvents workbench;

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
        var rejection = data(call(token, "/" + rejected, Map.of("decision", "REJECT")));
        assertThat(rejection.path("applied").asBoolean()).isFalse();
        assertThat(rejection.path("summary").asText()).contains("未执行", "跟进草稿");
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
    @Test void editedDetachedArgsAuditExpiryAndPersistedWorkbenchRoundTrip() throws Exception {
        String token = guests.create().accessToken(), owner = guests.require("Bearer " + token);
        var turn = conversations.begin(owner,null,"开始面试",AgentMode.INTERVIEWER,"fixture");
        var original = Map.<String,Object>of("role","前端工程师", "kind","项目与经历");
        var ticket = approvals.request(owner,turn.runId(),"startInterview",original);
        ticket.await().subscribe().dispose();
        conversations.finish(turn,"", "CANCELLED",null,null);
        var pending = data(call(token,"/pending",null)).get(0);
        assertThat(pending.path("editableFields").toString()).contains("role","kind");
        assertThat(call(token,"/"+ticket.id(),Map.of("decision","APPROVE","args",Map.of("kind","invalid"))).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT status FROM approvals WHERE id=?",String.class,ticket.id())).isEqualTo("PENDING");
        data(call(token,"/"+ticket.id(),Map.of("decision","APPROVE","args",Map.of("role","后端工程师"))));
        var audit = jdbc.queryForMap("SELECT args::text,edited_args::text,status FROM approvals WHERE id=?",ticket.id());
        assertThat(JSON.readTree((String)audit.get("args")).path("role").asText()).isEqualTo("前端工程师");
        assertThat(JSON.readTree((String)audit.get("edited_args")).path("role").asText()).isEqualTo("后端工程师");
        assertThat(jdbc.queryForObject("SELECT role FROM interview_sessions WHERE user_id=?",String.class,owner)).isEqualTo("后端工程师");

        var expiredTurn = conversations.begin(owner,null,"记住：我的目标岗位是后端工程师",AgentMode.AUTO,"fixture");
        var candidate = com.offerpilot.memory.application.MemoryCandidate.extract("记住：我的目标岗位是后端工程师");
        var memory = approvals.request(owner,expiredTurn.runId(),"saveUserFact",Map.of("kind",candidate.kind().name(),"fact",candidate.fact(),"source",candidate.source().name()));
        memory.await().subscribe().dispose();
        conversations.finish(expiredTurn,"", "CANCELLED",null,null);
        jdbc.update("UPDATE approvals SET expires_at=now()-interval '1 second' WHERE id=?",memory.id());
        approvalStore.expirePending();
        assertThat(data(call(token,"/pending",null))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM approvals WHERE id=?",String.class,memory.id())).isEqualTo("TIMEOUT");
        assertThat(call(token,"/"+memory.id(),Map.of("decision","APPROVE")).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_memories WHERE user_id=?",Integer.class,owner)).isZero();

        var excerptTurn = conversations.begin(owner,null,"记住：我的目标岗位是后端工程师",AgentMode.AUTO,"fixture");
        var excerptTicket = approvals.request(owner,excerptTurn.runId(),"saveUserFact",Map.of("kind",candidate.kind().name(),"fact",candidate.fact(),"source",candidate.source().name()));
        excerptTicket.await().subscribe().dispose();
        conversations.finish(excerptTurn,"", "CANCELLED",null,null);
        data(call(token,"/"+excerptTicket.id(),Map.of("decision","APPROVE","args",Map.of("fact","目标岗位是后端工程师"))));
        assertThat(jdbc.queryForObject("SELECT fact FROM user_memories WHERE user_id=?",String.class,owner)).isEqualTo("目标岗位是后端工程师");

        var now = java.time.Instant.now();
        var events = List.<com.offerpilot.agent.event.AgentEvent>of(
            new com.offerpilot.agent.event.AgentEvent.RunStarted("",1,turn.runId(),now,turn.conversationId(),turn.messageId(),"fixture",AgentMode.INTERVIEWER),
            new com.offerpilot.agent.event.AgentEvent.PlanCreated("",2,turn.runId(),now,"练习",List.of(new com.offerpilot.agent.event.AgentEvent.PlanStepView("s","准备",com.offerpilot.agent.domain.ExpertType.INTERVIEWER,com.offerpilot.agent.event.AgentEvent.StepStatus.PENDING,List.of()))),
            new com.offerpilot.agent.event.AgentEvent.StepUpdated("",3,turn.runId(),now,"s",com.offerpilot.agent.event.AgentEvent.StepStatus.RUNNING,null,null),
            new com.offerpilot.agent.event.AgentEvent.ToolCall("",4,turn.runId(),now,"s","c","startInterview",original,"local",com.offerpilot.agent.event.AgentEvent.ToolAccess.WRITE),
            new com.offerpilot.agent.event.AgentEvent.ToolResult("",5,turn.runId(),now,"s","c","startInterview",false,"未执行",12,false),
            new com.offerpilot.agent.event.AgentEvent.StepUpdated("",6,turn.runId(),now,"s",com.offerpilot.agent.event.AgentEvent.StepStatus.FAILED,"未执行",12),
            new com.offerpilot.agent.event.AgentEvent.RunCompleted("",7,turn.runId(),now,turn.messageId(),"COMPLETED",12));
        relay.start(owner,reactor.core.publisher.Flux.fromIterable(events)).collectList().block();
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/agent/runs/"+turn.runId()+"/workbench"))
                .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
        var snapshot = data(response);
        assertThat(snapshot).hasSize(7);
        assertThat(snapshot.get(3).path("source").asText()).isEqualTo("LOCAL");
        assertThat(snapshot.get(4).path("durationMs").asInt()).isEqualTo(12);
        assertThat(snapshot.get(4).path("result").path("summary").asText()).isEqualTo("未执行");
        // A fresh relay has no in-memory run; the existing events table still reconstructs the plan/tool states.
        var restarted = new com.offerpilot.agent.application.RunRelay(java.time.Duration.ZERO,java.time.Duration.ZERO,workbench);
        assertThat(restarted.history(owner,turn.runId())).isEqualTo(events);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> restarted.resume(owner,turn.runId(),5))
                .isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        var required = new com.offerpilot.agent.event.AgentEvent.ApprovalRequired("",8,turn.runId(),now,"s",ticket.id(),"c",
                "startInterview",original,"确认",ticket.expiresAt(),List.of("role","kind"));
        relay.start(owner,reactor.core.publisher.Flux.just(required)).collectList().block();
        assertThat(restarted.history(owner,turn.runId())).last().satisfies(event -> {
            assertThat(event).isInstanceOf(com.offerpilot.agent.event.AgentEvent.ApprovalResolved.class);
            assertThat(((com.offerpilot.agent.event.AgentEvent.ApprovalResolved)event).decision()).isEqualTo("APPROVE");
        });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> restarted.history(guests.require("Bearer "+guests.create().accessToken()),turn.runId()))
                .isInstanceOf(com.offerpilot.common.error.BusinessException.class);
    }

}
