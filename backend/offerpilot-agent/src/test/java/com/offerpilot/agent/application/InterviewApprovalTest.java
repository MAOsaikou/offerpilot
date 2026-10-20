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
    final StarStoryService stories = mock(StarStoryService.class);
    final InterviewWriteTools writes = new InterviewWriteTools(coach, approvals, stories);
    final ModelGateway gateway = mock(ModelGateway.class);
    {
        when(stories.candidates(any(), any())).thenReturn(java.util.List.of());
    }
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


    @Test void linkedStartPreservesProvenanceAndRejectedWritesStayUnchanged() throws Exception {
        var linked = InterviewIntentParser.prepare(new PlanStep("s0","面试",ExpertType.INTERVIEWER,Map.of("jobId","model_guessed"),List.of()),
                "开始面试",Map.of("interviewAction","startInterview","role","Java工程师","jobId","job_1"));
        when(sessions.create("alice","Java工程师","项目与经历","job_1")).thenReturn(session(false));
        var start=writes.request("alice","run_start",linked);
        assertThatThrownBy(() -> approvals.resolve("alice","run_start",start.id(),new ApprovalService.Decision("APPROVE",Map.of("jobId","job_other"),null))).isInstanceOf(BusinessException.class);
        var accepted=approvals.resolve("alice","run_start",start.id(),new ApprovalService.Decision("APPROVE",null,null));
        writes.execute("alice","run_start",linked,accepted).block();
        verify(sessions).create("alice","Java工程师","项目与经历","job_1");
        var registry = registry();
        var runner = new Orchestrator(new IntentPlanner(gateway, new ObjectMapper(), registry,
                new ByteArrayResource("{experts}".getBytes())), registry);
        for (boolean completed : List.of(false, true)) {
            when(sessions.get("alice", "iv_1")).thenReturn(session(completed));
            var reply = new ArrayList<Message>();
            var scoring = runner.run(AgentMode.INTERVIEWER, "给我这场面试的评分。", List.of(), ModelTier.FLASH,
                    "run_score", new AtomicInteger(), reply, u -> {}, "alice", Map.of("interviewSessionId", "iv_1"))
                    .collectList().block(Duration.ofSeconds(3));
            assertThat(scoring.stream().filter(AgentEvent.UiCard.class::isInstance).map(AgentEvent.UiCard.class::cast))
                    .extracting(AgentEvent.UiCard::cardType).containsExactly("interview_report", "interview_turn_score");
            assertThat(scoring).noneMatch(AgentEvent.ApprovalRequired.class::isInstance);
            assertThat(reply.stream().map(Message::getText).collect(java.util.stream.Collectors.joining()))
                    .contains("文字规则评分").doesNotContain("iv_1", "s1：", "四周主题");
        }
        var reply = new ArrayList<Message>();
        var completedAnswer = runner.run(AgentMode.INTERVIEWER, "我补充项目的验证方式", List.of(), ModelTier.FLASH,
                "run_answer", new AtomicInteger(), reply, u -> {}, "alice", Map.of("interviewSessionId", "iv_1"))
                .collectList().block(Duration.ofSeconds(3));
        assertThat(completedAnswer).noneMatch(e -> e instanceof AgentEvent.ApprovalRequired || e.type().equals("error"));
        assertThat(completedAnswer.stream().filter(AgentEvent.ToolCall.class::isInstance).map(AgentEvent.ToolCall.class::cast))
                .extracting(AgentEvent.ToolCall::access).containsExactly(AgentEvent.ToolAccess.READ);
        assertThat(reply.stream().map(Message::getText).collect(java.util.stream.Collectors.joining()))
                .contains("这场面试已完成", "本轮没有保存或推进答案", "评分、复盘", "新面试").doesNotContain("未完成，请重试", "iv_1");
        verifyNoInteractions(gateway);
        verify(sessions, never()).saveAnswer(any(), any(), anyInt(), any());
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
