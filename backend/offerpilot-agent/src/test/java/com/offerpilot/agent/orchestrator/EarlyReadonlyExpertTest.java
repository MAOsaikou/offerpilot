package com.offerpilot.agent.orchestrator;

import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.*;
import com.offerpilot.llm.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EarlyReadonlyExpertTest {
    @Test void everyOtherRegisteredActionAndMixedPlansRemainBehindGate() {
        var registry = new ExpertRegistry();
        for (var contract : registry.registeredTools()) {
            if (contract.expert() == null) continue;
            var step = new PlanStep("s1", "action", contract.expert(), Map.of("action", contract.action()), List.of());
            if (!registry.earlyReadOnly(step, Map.of())) assertHeld(List.of(step));
        }
        assertHeld(List.of(step("s1", ExpertType.RESUME, "advice"), step("s2", ExpertType.JOB, "search")));
    }

    @Test void auditedReadsExecuteEarlyAndTheSwitchRestoresOriginalOrdering() {
        for (var expert : List.of(ExpertType.RESUME, ExpertType.COACH, ExpertType.CAREER_DISCOVERY)) {
            String action = switch (expert) {
                case RESUME -> "advice"; case COACH -> "planLearning"; default -> "recommend";
            };
            for (boolean early : List.of(true, false)) {
                var fixture = new Fixture(List.of(step("s1", expert, action)));
                fixture.orchestrator.setEarlyReadonlyExpert(early);
                var subscription = fixture.work().subscribe(fixture.events::add);
                if (early) {
                    verify(fixture.experts).execute(anyString(), anyMap(), anyString(), any(), anyMap());
                    assertThat(fixture.events).extracting(AgentEvent::type).contains("plan.created", "tool.result");
                } else {
                    assertThat(fixture.events).isEmpty();
                    verify(fixture.experts, never()).execute(anyString(), anyMap(), anyString(), any(), anyMap());
                    fixture.gate.tryEmitEmpty();
                    verify(fixture.experts).execute(anyString(), anyMap(), anyString(), any(), anyMap());
                }
                subscription.dispose();
            }
        }
    }

    private void assertHeld(List<PlanStep> steps) {
        var fixture = new Fixture(steps);
        var subscription = fixture.work().subscribe(fixture.events::add);
        assertThat(fixture.events).as(steps.toString()).isEmpty();
        verify(fixture.experts, never()).execute(anyString(), anyMap(), anyString(), any(), anyMap());
        verify(fixture.experts, never()).prepareInterview(anyString(), any());
        verify(fixture.experts, never()).requestAnalysis(anyString(), anyString(), any(), anyMap());
        subscription.dispose();
    }

    private static PlanStep step(String id, ExpertType expert, String action) {
        return new PlanStep(id, "action", expert, Map.of("action", action), List.of());
    }

    private static class Fixture {
        final ExpertRegistry experts = spy(new ExpertRegistry());
        final IntentPlanner planner = mock(IntentPlanner.class);
        final Orchestrator orchestrator = new Orchestrator(planner, experts);
        final Sinks.Empty<Void> gate = Sinks.empty();
        final List<AgentEvent> events = new ArrayList<>();
        Fixture(List<PlanStep> steps) {
            when(planner.prepare(any(), anyString(), anyList(), any(), any(), anyMap())).thenReturn(Mono.just(
                    new IntentPlanner.Decision("single", steps.get(0).agent(), 1.0, false, null, new Plan("goal", steps))));
            doReturn(Mono.just(ExpertResult.text("只读结果"))).when(experts)
                    .execute(anyString(), anyMap(), anyString(), any(), anyMap());
        }
        Flux<AgentEvent> work() {
            return orchestrator.run(AgentMode.AUTO, "question", List.of(), ModelTier.FLASH, "run", new AtomicInteger(),
                    new ArrayList<Message>(), usage -> {}, "owner", Map.of())
                    .contextWrite(c -> c.put((String) ReflectionTestUtils.getField(ModelGateway.class, "EFFECTS_GATE"), gate.asMono()));
        }
    }
}
