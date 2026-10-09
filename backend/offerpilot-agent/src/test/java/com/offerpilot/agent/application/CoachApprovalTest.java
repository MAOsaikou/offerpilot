package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.*;
import com.offerpilot.agent.orchestrator.*;
import com.offerpilot.career.application.*;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.llm.*;
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

class CoachApprovalTest {
    final CoachPlanForChat coach = mock(CoachPlanForChat.class);
    final ConversationService conversations = mock(ConversationService.class);
    final ApprovalService approvals = new ApprovalService(conversations);
    final CoachWriteTools tools = new CoachWriteTools(coach,approvals);
    final ModelGateway gateway = mock(ModelGateway.class);
    final List<String> titles = List.of(GrowthTaskCatalog.TITLES.get(0));
    ExpertRegistry registry() {
        var beans = new StaticListableBeanFactory(); beans.addBean("coach",coach); beans.addBean("writes",tools);
        return new ExpertRegistry(beans.getBeanProvider(com.offerpilot.job.application.JobMatchForChat.class),
                beans.getBeanProvider(com.offerpilot.resume.application.ResumeDiagnosisForChat.class),null,null,null,null,
                beans.getBeanProvider(CareerRecommendForChat.class),beans.getBeanProvider(CareerWriteTools.class),
                beans.getBeanProvider(com.offerpilot.interview.application.InterviewCoachForChat.class),beans.getBeanProvider(InterviewWriteTools.class),
                beans.getBeanProvider(CoachPlanForChat.class),beans.getBeanProvider(CoachWriteTools.class));
    }
    Orchestrator runner(ExpertRegistry registry) throws Exception {
        return new Orchestrator(new IntentPlanner(gateway,new ObjectMapper(),registry,new ByteArrayResource("{experts}".getBytes())),registry);
    }
    PlanStep write() { return new PlanStep("s1","同步",ExpertType.COACH,Map.of("action","adoptGrowthPlan","taskTitles",titles),List.of()); }
    @ParameterizedTest @ValueSource(strings={"APPROVE","REJECT"})
    void orchestratorRequiresApprovalPinsSelectionsAndUsesTrustedOwner(String decision) throws Exception {
        var events = new CopyOnWriteArrayList<AgentEvent>(); var reply = new ArrayList<org.springframework.ai.chat.messages.Message>();
        var approved = new CompletableFuture<AgentEvent.ApprovalRequired>();
        var completed = runner(registry()).run(AgentMode.AUTO,"同步成长任务",List.of(),ModelTier.FLASH,"run",new AtomicInteger(),reply,u->{},"alice",
                Map.of("coachAction","adoptGrowthPlan","taskTitles",titles,"owner","attacker"))
                .doOnNext(e->{ events.add(e); if(e instanceof AgentEvent.ApprovalRequired a) approved.complete(a); }).then().toFuture();
        var ticket = approved.get(3,TimeUnit.SECONDS);
        verify(coach,never()).adoptGrowthPlan(anyString(),anyList());
        assertThat(ticket.editableFields()).isEmpty();
        assertThatThrownBy(()->approvals.resolve("attacker","run",ticket.approvalId(),new ApprovalService.Decision("APPROVE",null,null)))
                .hasMessageContaining("不存在");
        assertThatThrownBy(()->approvals.resolve("alice","run",ticket.approvalId(),new ApprovalService.Decision("APPROVE",Map.of("taskTitles",titles),null)))
                .hasMessageContaining("不允许编辑");
        approvals.resolve("alice","run",ticket.approvalId(),new ApprovalService.Decision(decision,Map.of(),null));
        completed.get(3,TimeUnit.SECONDS);
        assertThat(events).extracting(AgentEvent::type).containsExactly("plan.created","step.updated","tool.call","approval.required","approval.resolved","tool.result","step.updated");
        assertThat(((AgentEvent.ToolResult)events.get(5)).ok()).isEqualTo(decision.equals("APPROVE"));
        assertThat(events.toString()).doesNotContain("占位","placeholder.COACH");
        if(decision.equals("APPROVE")) verify(coach).adoptGrowthPlan("alice",titles);
        else verify(coach,never()).adoptGrowthPlan(anyString(),anyList());
        verifyNoInteractions(gateway);
    }
    @Test void timeoutCancelValidationAndDirectExecutionCannotWrite() {
        var ticket = approvals.request("alice","run","adoptGrowthPlan",Map.of("taskTitles",titles),Duration.ofMillis(5));
        var decision = ticket.await().block(Duration.ofSeconds(2));
        assertThatThrownBy(()->tools.execute("alice",write(),decision).block()).hasMessageContaining("超时");
        var cancelled = approvals.request("alice","run","adoptGrowthPlan",Map.of("taskTitles",titles));
        cancelled.await().subscribe().dispose();
        assertThatThrownBy(()->approvals.resolve("alice","run",cancelled.id(),new ApprovalService.Decision("APPROVE",null,null)))
                .isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        assertThatThrownBy(()->registry().execute("alice",Map.of(),"run",write(),Map.of()).block()).hasMessageContaining("必须先审批");
        for(var invalid : List.of(List.of("随意任务"),List.<String>of(),List.of(titles.get(0),titles.get(0))))
            assertThatThrownBy(()->CoachWriteTools.validate(Map.of("taskTitles",invalid))).hasMessageContaining("目录内");
        assertThatThrownBy(()->CoachWriteTools.validate(Map.of("taskTitles",titles,"owner","attacker"))).hasMessageContaining("目录内");
        verifyNoInteractions(coach);
    }
    @ParameterizedTest @ValueSource(strings={"帮我制定学习计划","我该补什么","技能差距","成长计划","补齐技能"})
    void autoRoutesWithoutModelAndEmitsBothCards(String phrase) throws Exception {
        var gap = new CoachPlanForChat.SkillGapPayload("req",new CoachPlanForChat.Target("OCCUPATION","occ","工程师"),List.of(),"summary",List.of());
        var roadmap = new CoachPlanForChat.RoadmapPayload(List.of(),List.of());
        when(coach.plan("alice","rs","rv",ExpertRegistry.requestId("run","s1"))).thenReturn(new CoachPlanForChat.Result("确定性建议未写入",gap,roadmap));
        var reply = new ArrayList<org.springframework.ai.chat.messages.Message>();
        var events = runner(registry()).run(AgentMode.AUTO,phrase,List.of(),ModelTier.FLASH,"run",new AtomicInteger(),reply,u->{},"alice",
                Map.of("resumeId","rs","resumeVersionId","rv")).collectList().block(Duration.ofSeconds(3));
        assertThat(events.stream().filter(e->e instanceof AgentEvent.UiCard).map(e->((AgentEvent.UiCard)e).cardType()))
                .containsExactly("skill_gap","roadmap");
        assertThat(events).extracting(AgentEvent::id).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,7).boxed().toList());
        assertThat(events.toString()).contains("planLearning").doesNotContain("占位","placeholder.COACH");
        assertThat(reply.get(0).getText()).contains("未写入任务");
        verifyNoInteractions(gateway);
    }
    @Test void guidanceAndUnavailableRegistryNeverReturnPlaceholderAndModelCannotInventSelections() throws Exception {
        var step = new PlanStep("s1","分析",ExpertType.COACH,Map.of(),List.of());
        assertThat(new ExpertRegistry().execute("alice",Map.of(),"run",step,Map.of()).block().summary()).contains("暂不可用").doesNotContain("占位");
        when(coach.plan(anyString(),any(),any(),anyString())).thenReturn(new CoachPlanForChat.Result("请先设为目标",null,null));
        var reply = new ArrayList<org.springframework.ai.chat.messages.Message>();
        var events = runner(registry()).run(AgentMode.COACH,"学习计划",List.of(),ModelTier.FLASH,"run",new AtomicInteger(),reply,u->{},"alice",Map.of())
                .collectList().block(Duration.ofSeconds(3));
        assertThat(events).noneMatch(e->e instanceof AgentEvent.UiCard);
        assertThat(reply.get(1).getText()).contains("设为目标");
        assertThat(reply.get(0).getText()).doesNotContain("占位","未成功");
        assertThat(CoachIntentParser.prepare(write(),"同步成长任务",Map.of()).inputs().get("action")).isEqualTo("clarify");
    }
}
