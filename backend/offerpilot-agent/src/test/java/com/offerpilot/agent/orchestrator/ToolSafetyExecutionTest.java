package com.offerpilot.agent.orchestrator;
import com.offerpilot.agent.application.ApprovalService;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.*;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.llm.ModelTier;
import com.offerpilot.job.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ToolSafetyExecutionTest {
    @Test void exhaustedSharedRunBudgetSuppressesMemoryWriteWithoutApproval() {
        var memories=mock(com.offerpilot.memory.application.UserMemoryService.class);
        var approvals=mock(ApprovalService.class);
        var memory=new com.offerpilot.agent.application.MemoryChat(memories,approvals,new ObjectMapper());
        var budget=new com.offerpilot.agent.tools.RunToolBudget(1);assertThat(budget.acquire()).isTrue();
        var events=memory.write("owner","run",new AtomicInteger(),null)
                .contextWrite(c -> c.put(com.offerpilot.agent.tools.RunToolBudget.KEY,budget)).collectList().block();
        assertThat(events).singleElement().satisfies(e -> assertThat(((AgentEvent.TextDelta)e).text()).contains("调用已达上限","未保存记忆"));
        verifyNoInteractions(memories,approvals);
    }
    private IntentPlanner planner(List<PlanStep> steps) {
        var planner=mock(IntentPlanner.class);
        when(planner.prepare(any(),anyString(),anyList(),any(),any(),anyMap()))
            .thenReturn(Mono.just(new IntentPlanner.Decision("single",null,1.0,false,null,new Plan("test",steps))));
        return planner;
    }
    @Test void iterationLimitSkipsRemainingStepsAndLeavesReadableContinuation() {
        var registry=spy(new ExpertRegistry());var reply=new ArrayList<org.springframework.ai.chat.messages.Message>();
        var steps=java.util.stream.IntStream.rangeClosed(1,3).mapToObj(i -> new PlanStep("s"+i,"read",ExpertType.JOB,Map.<String,Object>of(),List.<String>of())).toList();
        var runner=new Orchestrator(planner(steps),registry);runner.setMaxToolIterations(1);
        var events=runner.run(AgentMode.AUTO,"test",List.of(),ModelTier.FLASH,"run",new AtomicInteger(),reply,u->{},"owner",Map.of()).collectList().block();
        assertThat(events).filteredOn(AgentEvent.ToolCall.class::isInstance).hasSize(1);
        assertThat(events).filteredOn(e -> e instanceof AgentEvent.StepUpdated s && s.status()==AgentEvent.StepStatus.SKIPPED).hasSize(2);
        assertThat(reply.toString()).contains("工具调用上限","剩余任务未执行");
        verify(registry,times(1)).execute(anyString(),anyMap(),anyString(),any(),anyMap());
    }
    @Test void invalidArgumentsNeverRequestApprovalOrCallExpertAndAreModelReadable() {
        var registry=spy(new ExpertRegistry());
        var step=new PlanStep("s","write",ExpertType.JOB,Map.<String,Object>of("action","createApplication","jobId",123),List.<String>of());
        var runner=new Orchestrator(planner(List.of(step)),registry);
        var events=runner.run(AgentMode.AUTO,"test",List.of(),ModelTier.FLASH,"run",new AtomicInteger(),new ArrayList<>(),u->{},"owner",Map.of()).collectList().block();
        assertThat(events).noneMatch(AgentEvent.ApprovalRequired.class::isInstance);
        assertThat(events).filteredOn(AgentEvent.ToolResult.class::isInstance).singleElement().satisfies(e -> {
            var result=(AgentEvent.ToolResult)e;
            assertThat(result.ok()).isFalse();assertThat(result.resultPreview()).contains("TOOL_ARGUMENT_INVALID","$.jobId");
        });
        verify(registry,never()).requestBoard(any(),any(),any());
        verify(registry,never()).execute(anyString(),anyMap(),anyString(),any(),anyMap());
    }
    @Test void analysisPersistenceNeedsOwnerApprovalAndPinnedArguments() throws Exception {
        var matches=mock(JobMatchForChat.class);
        var report=new ObjectMapper().createObjectNode().put("id","match_fixture").put("score",80).put("summary","verified");report.putArray("requirements");
        when(matches.match(anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenReturn(JobMatchCards.fromReport(report));
        var registry=new ExpertRegistry(matches);
        var approvals=new ApprovalService(mock(ConversationService.class));registry.setAnalysisApprovals(approvals);
        var step=new PlanStep("s","match",ExpertType.JOB,Map.<String,Object>of("action","match"),List.<String>of());
        var runner=new Orchestrator(planner(List.of(step)),registry);
        for(String decision:List.of("REJECT","APPROVE")) {
            var pending=new CompletableFuture<AgentEvent.ApprovalRequired>();
            var result=runner.run(AgentMode.JOB,"match",List.of(),ModelTier.FLASH,decision,new AtomicInteger(),new ArrayList<>(),u->{},"owner",
                    Map.of("jobId","job_fixture","resumeId","resume_fixture","resumeVersionId","version_fixture"))
                .doOnNext(e -> { if(e instanceof AgentEvent.ApprovalRequired a) pending.complete(a); }).collectList().toFuture();
            var ticket=pending.get(3,TimeUnit.SECONDS);
            verifyNoInteractions(matches);
            assertThatThrownBy(() -> approvals.resolve("other",decision,ticket.approvalId(),new ApprovalService.Decision("APPROVE",null,null))).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
            assertThatThrownBy(() -> approvals.resolve("owner",decision,ticket.approvalId(),new ApprovalService.Decision("APPROVE",Map.of("resources",Map.of()),null))).hasMessageContaining("不允许编辑");
            approvals.resolve("owner",decision,ticket.approvalId(),new ApprovalService.Decision(decision,null,null));
            var events=result.get(3,TimeUnit.SECONDS);
            assertThat(events).filteredOn(AgentEvent.ToolCall.class::isInstance).singleElement().satisfies(e -> assertThat(((AgentEvent.ToolCall)e).access()).isEqualTo(AgentEvent.ToolAccess.WRITE));
            if("REJECT".equals(decision)) { verifyNoInteractions(matches); }
            else verify(matches).match(eq("owner"),eq("job_fixture"),eq("resume_fixture"),eq("version_fixture"),anyString(),eq(ModelTier.FLASH));
        }
    }
}
