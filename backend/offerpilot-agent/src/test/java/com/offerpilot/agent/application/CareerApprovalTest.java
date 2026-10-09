package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.agent.orchestrator.IntentPlanner;
import com.offerpilot.agent.orchestrator.Orchestrator;
import com.offerpilot.career.application.*;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CareerApprovalTest {
    final ConversationService conversations = mock(ConversationService.class);
    final CareerProfileService profiles = mock(CareerProfileService.class);
    final OccupationRecommender occupations = mock(OccupationRecommender.class);
    final CareerRecommendForChat careers = new DefaultCareerRecommendForChat(profiles, occupations);
    final ApprovalService approvals = new ApprovalService(conversations);
    final CareerWriteTools tools = new CareerWriteTools(careers, approvals);
    final ModelGateway gateway = mock(ModelGateway.class);

    ExpertRegistry registry() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("careers", careers);
        beans.addBean("careerWrites", tools);
        return new ExpertRegistry(beans.getBeanProvider(com.offerpilot.job.application.JobMatchForChat.class),
                beans.getBeanProvider(com.offerpilot.resume.application.ResumeDiagnosisForChat.class), null, null, null, null,
                beans.getBeanProvider(CareerRecommendForChat.class), beans.getBeanProvider(CareerWriteTools.class));
    }
    IntentPlanner planner(ExpertRegistry registry) throws Exception {
        return new IntentPlanner(gateway, new ObjectMapper(), registry, new ByteArrayResource("{experts}".getBytes()));
    }
    PlanStep write() { return new PlanStep("s1", "设置目标", ExpertType.CAREER_DISCOVERY,
            Map.of("action", "setCareerTarget", "occupationId", "occ1", "owner", "attacker"), List.of()); }
    void occupation() {
        when(occupations.find("occ1", null)).thenReturn(new OccupationRecommender.Ranked("occ1", "工程师", "技术", "IRC", 0));
        when(occupations.find("occ1", "工程师")).thenReturn(new OccupationRecommender.Ranked("occ1", "工程师", "技术", "IRC", 0));
        when(profiles.saveTarget("alice", "工程师")).thenReturn(new CareerProfileService.Profile("工程师", "上海", "Java", List.of("技术研发"), List.of(), null));
    }

    @ParameterizedTest @ValueSource(strings = {"APPROVE", "REJECT"})
    void structuredSelectionUsesApprovalEventsBeforeAnyWriteAndIsOwnerScoped(String decision) throws Exception {
        occupation();
        var registry = registry();
        var required = new CompletableFuture<AgentEvent.ApprovalRequired>();
        var completed = new CompletableFuture<Void>();
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var reply = new ArrayList<org.springframework.ai.chat.messages.Message>();
        new Orchestrator(planner(registry), registry).run(AgentMode.AUTO, "设为目标", List.of(), ModelTier.CHEAP,
                "run", new AtomicInteger(), reply, u -> {}, "alice",
                Map.of("careerAction", "setCareerTarget", "occupationId", "occ1", "owner", "attacker"))
                .subscribe(e -> { events.add(e); if (e instanceof AgentEvent.ApprovalRequired a) required.complete(a); },
                        completed::completeExceptionally, () -> completed.complete(null));
        var approval = required.get(3, TimeUnit.SECONDS);
        assertThat(approval.args()).isEqualTo(Map.of("occupationId", "occ1", "name", "工程师"));
        assertThat(approval.editableFields()).isEmpty();
        assertThat(((AgentEvent.ToolCall)events.get(2)).access()).isEqualTo(AgentEvent.ToolAccess.WRITE);
        verify(profiles, never()).saveTarget(anyString(), anyString());
        doThrow(new BusinessException(ErrorCode.RUN_NOT_FOUND)).when(conversations).requireRunOwner("other", "run");
        assertThatThrownBy(() -> approvals.resolve("other", "run", approval.approvalId(), new ApprovalService.Decision("APPROVE", null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> approvals.resolve("alice", "run", approval.approvalId(),
                new ApprovalService.Decision("APPROVE", Map.of("name", "其他职业"), null))).hasMessageContaining("不允许编辑");
        var resolved = approvals.resolve("alice", "run", approval.approvalId(), new ApprovalService.Decision(decision, Map.of(), null));
        assertThat(resolved.decision()).isEqualTo(decision);
        completed.get(3, TimeUnit.SECONDS);
        assertThat(events).extracting(AgentEvent::type).containsExactly("plan.created", "step.updated", "tool.call",
                "approval.required", "approval.resolved", "tool.result", "step.updated");
        assertThat(((AgentEvent.ToolResult) events.get(5)).ok()).isEqualTo(decision.equals("APPROVE"));
        assertThat(events.toString()).doesNotContain("占位专家");
        if (decision.equals("APPROVE")) {
            verify(profiles).saveTarget("alice", "工程师");
            assertThat(reply.get(1).getText()).contains("目标设为：工程师");
        } else verify(profiles, never()).saveTarget(anyString(), anyString());
        verify(profiles, never()).saveTarget(eq("attacker"), anyString());
        verifyNoInteractions(gateway);
        assertThatThrownBy(() -> approvals.resolve("alice", "run", approval.approvalId(), new ApprovalService.Decision("APPROVE", null, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test void timeoutRejectsWithoutWritingAndUnknownOccupationNeverRequestsApproval() {
        var ticket = approvals.request("alice", "run", "setCareerTarget", Map.of("occupationId", "occ1"), Duration.ofMillis(5));
        var decision = ticket.await().block(Duration.ofSeconds(2));
        assertThat(decision.decision()).isEqualTo("TIMEOUT");
        assertThatThrownBy(() -> tools.execute("alice", write(), decision).block()).hasMessageContaining("超时");
        when(occupations.find("occ1", null)).thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "不存在"));
        assertThatThrownBy(() -> tools.request("alice", "run2", write())).hasMessageContaining("不存在");
        verifyNoInteractions(profiles, conversations);
    }

    @Test void cancelledWaitCannotBeApprovedLaterOrWrite() {
        var ticket = approvals.request("alice", "run", "setCareerTarget", Map.of("occupationId", "occ1"));
        var waiting = ticket.await().subscribe();
        waiting.dispose();
        assertThatThrownBy(() -> approvals.resolve("alice", "run", ticket.id(),
                new ApprovalService.Decision("APPROVE", null, null))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(profiles, occupations);
    }

    @Test void explicitRecommendationEmitsCardAndMissingInterestsEmitGuidanceWithoutPlaceholder() throws Exception {
        var registry = registry();
        when(profiles.get("alice")).thenReturn(new CareerProfileService.Profile("", "", "", List.of("技术研发", "数据分析"), List.of(), null));
        when(occupations.rank("IRC", 8)).thenReturn(List.of(new OccupationRecommender.Ranked("occ1", "工程师", "技术", "IRC", 6)));
        var reply = new ArrayList<org.springframework.ai.chat.messages.Message>();
        var runner = new Orchestrator(planner(registry), registry);
        var events = runner.run(AgentMode.CAREER_DISCOVERY, "不知道适合什么", List.of(), ModelTier.CHEAP, "run",
                new AtomicInteger(), reply, u -> {}, "alice", Map.of()).collectList().block(Duration.ofSeconds(3));
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(AgentEvent.UiCard.class);
            assertThat(((AgentEvent.UiCard)e).cardType()).isEqualTo("career_recommendation");
        });
        assertThat(events.toString()).doesNotContain("占位");
        when(profiles.get("alice")).thenReturn(new CareerProfileService.Profile("", "", "", List.of(), List.of(), null));
        reply.clear();
        var guidance = runner.run(AgentMode.CAREER_DISCOVERY, "不知道适合什么", List.of(), ModelTier.CHEAP, "run2",
                new AtomicInteger(), reply, u -> {}, "alice", Map.of()).collectList().block(Duration.ofSeconds(3));
        assertThat(guidance).noneMatch(e -> e instanceof AgentEvent.UiCard);
        assertThat(reply.get(1).getText()).contains("勾选兴趣");
        assertThat(reply.get(0).getText()).doesNotContain("未成功", "占位");
    }
}
