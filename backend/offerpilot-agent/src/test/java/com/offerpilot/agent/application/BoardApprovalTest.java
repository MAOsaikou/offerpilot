package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.agent.orchestrator.IntentPlanner;
import com.offerpilot.agent.orchestrator.Orchestrator;
import com.offerpilot.board.application.ApplicationService;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BoardApprovalTest {
    final ConversationService conversations = mock(ConversationService.class);
    final ApplicationService applications = mock(ApplicationService.class);
    final ApprovalService approvals = new ApprovalService(conversations);
    final BoardWriteTools boards = new BoardWriteTools(applications, approvals);

    @SuppressWarnings("unchecked")
    ExpertRegistry registry(BoardWriteTools tools) {
        return new ExpertRegistry(mock(ObjectProvider.class), mock(ObjectProvider.class), null, null, null, tools);
    }


    @Test void approvedCreateDefaultsToTodoAndNoteOnlyUpdateDoesNotInventAStatus() {
        var create = step(Map.of("action", "createApplication", "jobId", "job1", "note", "收藏备注"));
        var ticket = boards.request("alice", "run_create", create);
        assertThat(ticket.args()).containsEntry("status", "TODO");
        verifyNoInteractions(applications);
        when(applications.create("alice", "job1", "TODO", "收藏备注")).thenReturn(saved("TODO"));
        var accepted = approvals.resolve("alice", "run_create", ticket.id(), new ApprovalService.Decision("APPROVE", null, null));
        var created = boards.execute("alice", "run_create", create, accepted).block(Duration.ofSeconds(3));
        assertThat(new ObjectMapper().valueToTree(created.card().payload()).path("change").asText()).isEqualTo("CREATED");
        verify(applications).create("alice", "job1", "TODO", "收藏备注");

        var update = step(Map.of("action", "updateApplicationStatus", "applicationId", "app1", "note", ""));
        var noteTicket = boards.request("alice", "run_note", update);
        when(applications.update("alice", "app1", null, "")).thenReturn(saved("TODO"));
        boards.execute("alice", "run_note", update, approvals.resolve("alice", "run_note", noteTicket.id(),
                new ApprovalService.Decision("APPROVE", null, null))).block(Duration.ofSeconds(3));
        verify(applications).update("alice", "app1", null, "");
    }

    @Test void rejectAndTimeoutResolveWithoutMutation() {
        for (boolean timeout : List.of(false, true)) {
            ApprovalService service = timeout ? new ApprovalService(conversations) {
                @Override public Ticket request(String owner, String run, String action, Map<String,Object> args) {
                    return request(owner, run, action, args, Duration.ofMillis(30));
                }
            } : approvals;
            var planner = mock(IntentPlanner.class);
            when(planner.prepare(any(), anyString(), anyList(), any(), any(), anyMap())).thenReturn(Mono.just(
                    new IntentPlanner.Decision("single", ExpertType.JOB, 1.0, false, null, new Plan("新建", List.of(step(Map.of("action", "createApplication", "jobId", "job1")))))));
            var events = new Orchestrator(planner, registry(new BoardWriteTools(applications, service))).run(AgentMode.AUTO, "新建", List.of(), ModelTier.FLASH,
                    "run_reject", new AtomicInteger(), new ArrayList<>(), u -> {}, "alice", Map.of()).doOnNext(event -> {
                        if (!timeout && event instanceof AgentEvent.ApprovalRequired required)
                            service.resolve("alice", "run_reject", required.approvalId(), new ApprovalService.Decision("REJECT", null, null));
                    }).collectList().block(Duration.ofSeconds(3));
            assertThat(events).anySatisfy(e -> {
                assertThat(e).isInstanceOf(AgentEvent.ApprovalResolved.class);
                assertThat(((AgentEvent.ApprovalResolved)e).decision()).isEqualTo(timeout ? "TIMEOUT" : "REJECT");
            });
            assertThat(events).anySatisfy(e -> {
                assertThat(e).isInstanceOf(AgentEvent.ToolResult.class);
                assertThat(((AgentEvent.ToolResult)e).ok()).isFalse();
                assertThat(((AgentEvent.ToolResult)e).resultPreview()).isEqualTo(timeout ? "审批已超时" : "用户拒绝了该操作");
            });
            assertThat(events).noneMatch(AgentEvent.UiCard.class::isInstance);
        }
        verifyNoInteractions(applications);
    }

    @Test void validatesIdsStatusNotesAndRestrictsEditsAndOwner() {
        for (var args : List.of(Map.of("action", "createApplication"), Map.of("action", "updateApplicationStatus", "applicationId", "app1"),
                Map.of("action", "createApplication", "jobId", "job1", "status", "WRITTEN_TEST"),
                Map.of("action", "createApplication", "jobId", "job1", "note", "字".repeat(2001))))
            assertThatThrownBy(() -> boards.request("alice", "run", step(args))).isInstanceOf(BusinessException.class);
        var ticket = boards.request("alice", "run", step(Map.of("action", "createApplication", "jobId", "job1")));
        for (var edits : List.of(Map.of("jobId", "job2"), Map.of("owner", "other"), Map.of("status", "WRITTEN_TEST")))
            assertThatThrownBy(() -> approvals.resolve("alice", "run", ticket.id(), new ApprovalService.Decision("APPROVE", new HashMap<>(edits), null))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> approvals.resolve("other", "run", ticket.id(), new ApprovalService.Decision("APPROVE", null, null))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> approvals.resolve("alice", "run", ticket.id(), new ApprovalService.Decision("MAYBE", null, null))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> approvals.resolve("alice", "run", "missing", new ApprovalService.Decision("APPROVE", null, null))).isInstanceOf(BusinessException.class);
        approvals.resolve("alice", "run", ticket.id(), new ApprovalService.Decision("REJECT", null, null));
        verifyNoInteractions(applications);
    }

    @Test void expiredAndCancelledWaitCannotBeApproved() {
        var ticket = approvals.request("alice", "run", "createApplication", Map.of("jobId", "job1", "status", "TODO"), Duration.ZERO);
        assertThatThrownBy(() -> approvals.resolve("alice", "run", ticket.id(), new ApprovalService.Decision("APPROVE", null, null))).isInstanceOf(BusinessException.class);
        assertThat(ticket.await().block(Duration.ofSeconds(1)).decision()).isEqualTo("TIMEOUT");
        var pending = boards.request("alice", "run", step(Map.of("action", "createApplication", "jobId", "job1")));
        pending.await().subscribe().dispose();
        assertThatThrownBy(() -> approvals.resolve("alice", "run", pending.id(), new ApprovalService.Decision("APPROVE", null, null))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(applications);
    }

    PlanStep step(Map<String, ?> args) { return new PlanStep("s1", "看板", ExpertType.JOB, new HashMap<>(args), List.of()); }
    ApplicationService.Application saved(String status) {
        return new ApplicationService.Application("app1", "job1", status, "周五面试", "", "", new ObjectMapper().createObjectNode().put("company", "示例公司").put("title", "Java"));
    }
    @Test void selectedJobNaturalAddIsDeterministicAndStillWaitsForApproval() throws Exception {
        for (var tier : List.of(ModelTier.FLASH, ModelTier.PRO)) {
            var gateway = mock(com.offerpilot.llm.ModelGateway.class);
            var registry = registry(boards);
            var parser = new com.offerpilot.agent.orchestrator.JobIntentParser(gateway, new ObjectMapper(), new org.springframework.core.io.ByteArrayResource("board".getBytes()));
            var planner = new IntentPlanner(gateway, new ObjectMapper(), registry, new org.springframework.core.io.ByteArrayResource("planner".getBytes()), parser, null);
            var events = new CopyOnWriteArrayList<AgentEvent>();
            var subscription = new Orchestrator(planner, registry).run(AgentMode.AUTO, "把这个岗位加入求职看板", List.of(), tier,
                "board-" + tier, new AtomicInteger(), new ArrayList<>(), u -> {}, "alice", Map.of("jobId", "job1")).subscribe(events::add);
            assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOf(AgentEvent.ApprovalRequired.class));
            assertThat(events).filteredOn(AgentEvent.ToolCall.class::isInstance).singleElement().satisfies(e ->
                assertThat(((AgentEvent.ToolCall)e).access()).isEqualTo(AgentEvent.ToolAccess.WRITE));
            verifyNoInteractions(gateway, applications);
            subscription.dispose();
        }
    }
}
