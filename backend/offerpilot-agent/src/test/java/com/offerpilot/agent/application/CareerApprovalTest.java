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

    @Test void rerankIsAWriteAndOnlyPersistsAfterOwnerApproval() throws Exception {
        var reranks = mock(CareerRerankForChat.class);
        tools.setReranks(reranks);
        var constraints = new CareerRecommendationService.Constraints("上海", 30, true, true);
        var saved = new CareerRecommendationService.Revision("new", "parent", constraints, List.of(), 1, false, "");
        when(reranks.rerank("alice", "parent", constraints)).thenReturn(saved);
        var registry = registry();
        var runner = new Orchestrator(planner(registry), registry);
        Map<String, Object> context = Map.of("careerAction", "rerankCareers", "revisionId", "parent",
                "city", "上海", "minSalaryK", 30, "avoidOvertime", true, "research", true, "owner", "attacker");
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var pending = new CompletableFuture<AgentEvent.ApprovalRequired>();
        var finished = runner.run(AgentMode.AUTO, "按约束重排", List.of(), ModelTier.FLASH, "run", new AtomicInteger(),
                new ArrayList<>(), u -> {}, "alice", context).doOnNext(e -> {
                    events.add(e);
                    if (e instanceof AgentEvent.ApprovalRequired approval) pending.complete(approval);
                }).collectList().toFuture();
        var approval = pending.get(3, TimeUnit.SECONDS);
        assertThat(approval.name()).isEqualTo("rerankCareers");
        assertThat(approval.reason()).contains("新的推荐修订");
        verify(reranks, never()).rerank(anyString(), anyString(), any());
        assertThatThrownBy(() -> approvals.resolve("mallory", "run", approval.approvalId(),
                new ApprovalService.Decision("APPROVE", null, null))).isInstanceOf(BusinessException.class);
        approvals.resolve("alice", "run", approval.approvalId(), new ApprovalService.Decision("APPROVE", null, null));
        finished.get(3, TimeUnit.SECONDS);
        verify(reranks).rerank("alice", "parent", constraints);
        verifyNoInteractions(gateway);
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.ToolCall call && call.access() == AgentEvent.ToolAccess.WRITE);
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.UiCard card && card.cardType().equals("career_recommendation"));
        var step = new PlanStep("s", "重排", ExpertType.CAREER_DISCOVERY, Map.of("action", "rerankCareers"), List.of());
        assertThatThrownBy(() -> registry.execute("alice", Map.of(), "run", step, Map.of()).block()).hasMessageContaining("必须先审批");
        assertThatThrownBy(() -> tools.execute("alice", step, new ApprovalService.Decision("REJECT", Map.of(), null)).block())
                .hasMessageContaining("拒绝");
        verifyNoInteractions(profiles);
    }

    @Test void recommendationCarriesCurrentProfileWithoutChangingExistingTarget() {
        when(profiles.get("alice")).thenReturn(new CareerProfileService.Profile("工程师", "上海", "Java", List.of("技术研发"), List.of(), null));
        when(occupations.rankHybrid(any(RiasecProfile.class), anyString(), eq(8))).thenReturn(List.of());
        var result = registry().execute("alice", Map.of(), "run", new PlanStep("s1", "探索", ExpertType.CAREER_DISCOVERY,
                Map.of("request", "推荐职业"), List.of()), Map.of()).block(Duration.ofSeconds(3));
        assertThat(result.summary()).contains("本轮未修改职业档案", "当前职业档案", "上海", "工程师").doesNotContain("未设置目标");
        assertThat(result.card().cardType()).isEqualTo("career_recommendation");
        verify(profiles, never()).saveTarget(anyString(), anyString());
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

    @Test void approvedTargetGeneratesFollowupCardsThroughOrchestratorAndAsksForConfirmation() throws Exception {
        occupation();
        var followups=mock(CareerTargetPlans.class);tools.setTargetPlans(followups);
        var result=new ObjectMapper().readTree("{\"jobs\":{\"items\":[]},\"learning\":{\"skillGap\":{\"skills\":[]},\"roadmap\":{\"weeks\":[]}}}");
        when(followups.get("alice")).thenReturn(new CareerTargetPlans.Plan("revision","工程师","上海","SUGGESTED",result,""));
        var registry=registry();var runner=new Orchestrator(planner(registry),registry);
        var events=new CopyOnWriteArrayList<AgentEvent>();var pending=new CompletableFuture<AgentEvent.ApprovalRequired>();
        var finished=runner.run(AgentMode.AUTO,"设为目标",List.of(),ModelTier.FLASH,"target-run",new AtomicInteger(),
            new ArrayList<>(),u->{},"alice",Map.of("careerAction","setCareerTarget","occupationId","occ1"))
            .doOnNext(e->{events.add(e);if(e instanceof AgentEvent.ApprovalRequired approval) pending.complete(approval);}).collectList().toFuture();
        var approval=pending.get(3,TimeUnit.SECONDS);verifyNoInteractions(followups);
        approvals.resolve("alice","target-run",approval.approvalId(),new ApprovalService.Decision("APPROVE",null,null));
        finished.get(3,TimeUnit.SECONDS);
        verify(followups).generate("alice");
        assertThat(events).anyMatch(e->e instanceof AgentEvent.UiCard card && card.cardType().equals("skill_gap"));
        assertThat(events).anyMatch(e->e instanceof AgentEvent.UiCard card && card.cardType().equals("roadmap"));
        var direct=tools.execute("alice",write(),new ApprovalService.Decision("APPROVE",Map.of("occupationId","occ1","name","工程师"),null)).block();
        assertThat(direct.summary()).contains("是否按这些建议继续", "确认采用", "未修改投递记录");
        clearInvocations(followups);
        assertThatThrownBy(()->tools.execute("alice",write(),new ApprovalService.Decision("REJECT",Map.of(),null)).block()).hasMessageContaining("拒绝");
        verifyNoInteractions(followups);
    }
    @Test void followupFailureDoesNotMisreportAnAlreadySavedTarget() {
        occupation();var followups=mock(CareerTargetPlans.class);tools.setTargetPlans(followups);
        doThrow(new IllegalStateException("fixture failure")).when(followups).generate("alice");
        var result=tools.execute("alice",write(),new ApprovalService.Decision("APPROVE",Map.of("occupationId","occ1","name","工程师"),null)).block();
        assertThat(result.summary()).contains("目标设为：工程师", "已排队");verify(profiles).saveTarget("alice","工程师");
    }

}
