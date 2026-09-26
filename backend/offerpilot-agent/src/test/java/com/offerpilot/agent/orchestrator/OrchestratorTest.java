package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.agent.experts.ExpertResult;
import com.offerpilot.llm.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrchestratorTest {
    private final ModelGateway gateway = mock(ModelGateway.class);
    private final ExpertRegistry experts = spy(new ExpertRegistry());
    private final List<Message> reply = new ArrayList<>();
    private IntentPlanner planner;
    private Orchestrator orchestrator;

    @BeforeEach void setup() throws Exception {
        planner = new IntentPlanner(gateway, new ObjectMapper(), experts, new ByteArrayResource("{experts}".getBytes()));
        orchestrator = new Orchestrator(planner, experts);
    }

    private void model(String json) {
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(json))))));
    }

    private Flux<AgentEvent> run(AgentMode mode) {
        return orchestrator.run(mode, "分析岗位并指导简历", List.of(), ModelTier.CHEAP, "run", new AtomicInteger(), reply, u -> {}, "owner", Map.of());
    }

    private String composite() {
        return """
                {"kind":"composite","confidence":0.9,"plan":{"goal":"求职准备","steps":[
                {"id":"s2","title":"简历指导","agent":"RESUME","inputs":{"jobFrom":"s1"},"dependsOn":["s1"]},
                {"id":"s1","title":"岗位分析","agent":"JOB","inputs":{},"dependsOn":[]},
                {"id":"s3","title":"独立练习","agent":"COACH","inputs":{},"dependsOn":[]}]}}
                """;
    }

    @Test void compositeIsSortedAndExecutedSeriallyWithCorrelatedEventsAndUpstreamOutputs() {
        model(composite());
        var events = run(AgentMode.AUTO).collectList().block(Duration.ofSeconds(3));
        assertThat(events).extracting(AgentEvent::type).containsExactly("plan.created",
                "step.updated", "tool.call", "tool.result", "step.updated",
                "step.updated", "tool.call", "tool.result", "step.updated",
                "step.updated", "tool.call", "tool.result", "step.updated");
        assertThat(events).extracting(AgentEvent::id).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,13).boxed().toList());
        var plan = (AgentEvent.PlanCreated) events.get(0);
        assertThat(plan.steps()).extracting(AgentEvent.PlanStepView::id).containsExactly("s1", "s2", "s3");
        verify(experts).execute(eq("owner"), eq(Map.of()), eq("run"), argThat(s -> s.id().equals("s2")), argThat(upstream -> upstream.get("s1").contains("占位")));
        var call = (AgentEvent.ToolCall) events.get(2);
        var result = (AgentEvent.ToolResult) events.get(3);
        assertThat(result.toolCallId()).isEqualTo(call.toolCallId());
        assertThat(result.resultPreview()).contains("未执行搜索");
        assertThat(reply.get(0).getText()).contains("未执行真实业务操作");
    }

    @Test void failureSkipsDependentsAndStillRunsIndependentStepsWithoutLeakingException() {
        model(composite());
        doReturn(Mono.error(new RuntimeException("private-secret"))).when(experts).execute(any(), any(), any(), argThat(s -> s.id().equals("s1")), anyMap());
        var events = run(AgentMode.AUTO).collectList().block(Duration.ofSeconds(3));
        assertThat(events).filteredOn(e -> e instanceof AgentEvent.StepUpdated)
                .map(e -> ((AgentEvent.StepUpdated)e).status()).containsExactly(AgentEvent.StepStatus.RUNNING,
                        AgentEvent.StepStatus.FAILED, AgentEvent.StepStatus.SKIPPED, AgentEvent.StepStatus.RUNNING, AgentEvent.StepStatus.DONE);
        verify(experts, never()).execute(any(), any(), any(), argThat(s -> s.id().equals("s2")), anyMap());
        assertThat(events.toString()).doesNotContain("private-secret");
    }

    @Test void explicitModeSkipsModelRecognition() {
        for (var mode : AgentMode.values()) {
            if (mode == AgentMode.AUTO) continue;
            var events = run(mode).collectList().block(Duration.ofSeconds(3));
            assertThat(((AgentEvent.PlanCreated)events.get(0)).steps().get(0).agent().name()).isEqualTo(mode.name());
        }
        verifyNoInteractions(gateway);
    }

    @Test void invalidPlanRetriesTwiceThenFallsBackWithoutExecuting() {
        model(composite().replace("\"JOB\"", "\"UNKNOWN\""));
        assertThat(run(AgentMode.AUTO).collectList().block(Duration.ofSeconds(3))).isEmpty();
        verify(gateway, times(3)).stream(any(), anyList(), eq(0.1));
        verify(experts, never()).execute(any(), any(), any(), any(), anyMap());
        assertThat(reply.get(0).getText()).contains("降级");
    }

    @Test void chitchatClarificationAndUnsafeDoNotRunExperts() {
        for (String json : List.of("{\"kind\":\"chitchat\",\"confidence\":1}",
                "{\"kind\":\"single\",\"confidence\":0.2,\"needsClarification\":true,\"clarifyingQuestion\":\"目标是什么？\"}",
                "{\"kind\":\"unsafe\",\"confidence\":1}")) {
            model(json);
            assertThat(run(AgentMode.AUTO).collectList().block(Duration.ofSeconds(3))).isEmpty();
        }
        verify(experts, never()).execute(any(), any(), any(), any(), anyMap());
        assertThat(reply).hasSize(2);
    }

    @Test void cancellationStopsCurrentExpertAndNeverStartsNext() {
        model(composite());
        AtomicBoolean cancelled = new AtomicBoolean();
        doReturn(Mono.<ExpertResult>never().doOnCancel(() -> cancelled.set(true))).when(experts).execute(any(), any(), any(), any(), anyMap());
        var subscription = run(AgentMode.AUTO).subscribe();
        subscription.dispose();
        assertThat(cancelled).isTrue();
        verify(experts, times(1)).execute(any(), any(), any(), any(), anyMap());
        assertThat(reply).isEmpty();
    }

    @Test void validationRejectsCyclesUnknownDependenciesDuplicatesTooManyStepsAndInvalidReferences() {
        var a = new PlanStep("a", "岗位", ExpertType.JOB, Map.of(), List.of("b"));
        var b = new PlanStep("b", "简历", ExpertType.RESUME, Map.of(), List.of("a"));
        for (var steps : List.of(List.of(a,b), List.of(a), List.of(b,b), Collections.nCopies(9,a),
                List.of(new PlanStep("a", "岗位", ExpertType.JOB, Map.of("jobFrom", "missing"), List.of())))) {
            assertThatThrownBy(() -> PlanValidator.validate(new Plan("目标", steps))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> planner.decode("{\"kind\":\"chitchat\",\"confidence\":1} {}", "hi"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
