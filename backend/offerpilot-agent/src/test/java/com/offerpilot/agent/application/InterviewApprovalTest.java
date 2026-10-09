package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.agent.orchestrator.*;
import com.offerpilot.interview.application.*;
import com.offerpilot.common.error.*;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InterviewApprovalTest {
    final InterviewService sessions = mock(InterviewService.class);
    final InterviewCoachForChat coach = new DefaultInterviewCoachForChat(sessions);
    final ConversationService conversations = mock(ConversationService.class);
    final ApprovalService approvals = new ApprovalService(conversations);
    final InterviewWriteTools writes = new InterviewWriteTools(coach, approvals);
    final ModelGateway gateway = mock(ModelGateway.class);
    InterviewService.Session session(boolean completed) {
        return new InterviewService.Session("iv_1", "Java工程师", "项目与经历", 0, completed, "now", "snapshot",
                java.util.stream.IntStream.range(0, 5).mapToObj(i -> new InterviewService.Answer(i, InterviewService.QUESTIONS.get(i), "")).toList());
    }
    ExpertRegistry registry() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("interviews", coach); beans.addBean("writes", writes);
        return new ExpertRegistry(beans.getBeanProvider(com.offerpilot.job.application.JobMatchForChat.class),
                beans.getBeanProvider(com.offerpilot.resume.application.ResumeDiagnosisForChat.class), null, null, null, null,
                beans.getBeanProvider(com.offerpilot.career.application.CareerRecommendForChat.class),
                beans.getBeanProvider(CareerWriteTools.class), beans.getBeanProvider(InterviewCoachForChat.class), beans.getBeanProvider(InterviewWriteTools.class));
    }
    @ParameterizedTest @CsvSource({"startInterview,APPROVE", "startInterview,REJECT", "continueInterview,APPROVE", "continueInterview,REJECT", "completeInterview,APPROVE", "completeInterview,REJECT"})
    void everyChatWriteWaitsForApprovalAndRejectNeverMutates(String action, String decision) throws Exception {
        when(sessions.create("alice", "Java工程师", "项目与经历")).thenReturn(session(false));
        when(sessions.get("alice", "iv_1")).thenReturn(session(false));
        when(sessions.getForUpdate("alice", "iv_1")).thenReturn(session(false));
        when(sessions.saveAnswer("alice", "iv_1", 0, "我负责接口")).thenReturn(session(false));
        when(sessions.update("alice", "iv_1", null, true)).thenReturn(session(true));
        var registry = registry();
        var planner = new IntentPlanner(gateway, new ObjectMapper(), registry, new ByteArrayResource("{experts}".getBytes()));
        var required = new CompletableFuture<AgentEvent.ApprovalRequired>();
        var finished = new CompletableFuture<Void>();
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var reply = new ArrayList<Message>();
        new Orchestrator(planner, registry).run(AgentMode.AUTO, "我负责接口", List.of(), ModelTier.FLASH, "run",
                new AtomicInteger(), reply, u -> {}, "alice", Map.of("interviewAction", action, "interviewSessionId", "iv_1", "role", "Java工程师", "owner", "bob"))
                .subscribe(e -> { events.add(e); if (e instanceof AgentEvent.ApprovalRequired a) required.complete(a); }, finished::completeExceptionally, () -> finished.complete(null));
        var approval = required.get(3, TimeUnit.SECONDS);
        assertThat(approval.editableFields()).isEmpty();
        assertThat(((AgentEvent.PlanCreated) events.get(0)).steps().get(0).title()).doesNotContain("占位");
        assertThat(((AgentEvent.ToolCall) events.get(2)).access()).isEqualTo(AgentEvent.ToolAccess.WRITE);
        verify(sessions, never()).create(any(), any(), any());
        verify(sessions, never()).saveAnswer(any(), any(), anyInt(), any());
        verify(sessions, never()).update(any(), any(), any(), any());
        assertThatThrownBy(() -> approvals.resolve("alice", "run", approval.approvalId(), new ApprovalService.Decision("APPROVE", Map.of("sessionId", "iv_other"), null))).hasMessageContaining("不允许编辑");
        assertThatThrownBy(() -> approvals.resolve("bob", "run", approval.approvalId(), new ApprovalService.Decision("APPROVE", null, null))).isInstanceOf(BusinessException.class);
        approvals.resolve("alice", "run", approval.approvalId(), new ApprovalService.Decision(decision, null, null));
        finished.get(3, TimeUnit.SECONDS);
        assertThat(events.toString()).doesNotContain("占位专家", "placeholder.INTERVIEWER");
        assertThat(events).extracting(AgentEvent::type).contains("approval.required", "approval.resolved", "tool.result");
        if (decision.equals("REJECT")) {
            verify(sessions, never()).create(any(), any(), any());
            verify(sessions, never()).saveAnswer(any(), any(), anyInt(), any());
            verify(sessions, never()).update(any(), any(), any(), any());
        } else if (action.equals("startInterview")) verify(sessions).create("alice", "Java工程师", "项目与经历");
        else if (action.equals("continueInterview")) verify(sessions).saveAnswer("alice", "iv_1", 0, "我负责接口");
        else verify(sessions).update("alice", "iv_1", null, true);
        verifyNoInteractions(gateway);
    }
    @Test void readOnlyScoreRoutesBareModelStepEmitsReportWithoutApprovalAndUsesOwner() throws Exception {
        when(sessions.list("alice")).thenReturn(List.of(new InterviewService.Summary("iv_1", "Java工程师", "项目与经历", 0, false, "", "")));
        when(sessions.get("alice", "iv_1")).thenReturn(session(false));
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"kind\":\"single\",\"expert\":\"INTERVIEWER\",\"confidence\":1}"))))));
        var registry = registry();
        var planner = new IntentPlanner(gateway, new ObjectMapper(), registry, new ByteArrayResource("{experts}".getBytes()));
        var events = new Orchestrator(planner, registry).run(AgentMode.AUTO, "评价刚才的面试", List.of(), ModelTier.FLASH,
                "run", new AtomicInteger(), new ArrayList<>(), u -> {}, "alice", Map.of()).collectList().block(Duration.ofSeconds(3));
        assertThat(events).noneMatch(e -> e instanceof AgentEvent.ApprovalRequired);
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.UiCard c && c.cardType().equals("interview_report"));
        assertThat(events.toString()).doesNotContain("占位专家");
        verifyNoInteractions(conversations);
        verify(sessions, never()).update(any(), any(), any(), any());
    }
    @Test void invalidKindOtherOwnerTimeoutAndCancellationNeverWrite() {
        var bad = new PlanStep("s1", "面试", ExpertType.INTERVIEWER, Map.of("action", "startInterview", "kind", "硬件指标"), List.of());
        assertThatThrownBy(() -> writes.request("alice", "run", bad)).isInstanceOf(BusinessException.class);
        when(sessions.get("bob", "iv_1")).thenThrow(new BusinessException(ErrorCode.INTERVIEW_NOT_FOUND));
        var step = new PlanStep("s1", "结束", ExpertType.INTERVIEWER, Map.of("action", "completeInterview", "sessionId", "iv_1"), List.of());
        assertThatThrownBy(() -> writes.request("bob", "run", step)).isInstanceOf(BusinessException.class);
        var args = Map.<String, Object>of("sessionId", "iv_1", "expectedUpdatedAt", "snapshot");
        var ticket = approvals.request("alice", "run", "completeInterview", args, Duration.ofMillis(5));
        assertThatThrownBy(() -> writes.execute("alice", "run", step, ticket.await().block(Duration.ofSeconds(2))).block()).hasMessageContaining("超时");
        var cancelled = approvals.request("alice", "cancel", "completeInterview", args);
        cancelled.await().subscribe().dispose();
        assertThatThrownBy(() -> approvals.resolve("alice", "cancel", cancelled.id(), new ApprovalService.Decision("APPROVE", null, null))).isInstanceOf(BusinessException.class);
        verify(sessions, never()).update(any(), any(), any(), any());
    }
}
