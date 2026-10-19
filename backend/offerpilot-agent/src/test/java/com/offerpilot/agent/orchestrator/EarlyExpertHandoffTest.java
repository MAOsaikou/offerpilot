package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import com.offerpilot.llm.quota.LlmUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EarlyExpertHandoffTest {
    private static final String PREFIX = "{\"kind\":\"single\",\"expert\":\"RESUME\",\"confidence\":0.95,\"needsClarification\":false,";
    private ChatResponse chunk(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }

    @Test void adoptsOneExpertCallStartedBeforePlannerTailAndPreservesOwnerAndUsage() throws Exception {
        var gateway = mock(ModelGateway.class);
        var resumes = mock(ResumeIntentParser.class);
        var planner = planner(gateway, resumes);
        var tail = Sinks.<ChatResponse>one();
        var started = new AtomicBoolean();
        var dropped = new AtomicBoolean();
        var tokens = new AtomicInteger();
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(chunk(PREFIX)).concatWith(tail.asMono()));
        when(resumes.prepare(any(), anyString(), anyList(), any(), any(), anyMap())).thenAnswer(call -> {
            PlanStep step = call.getArgument(0);
            java.util.function.Consumer<org.springframework.ai.chat.metadata.Usage> usage = call.getArgument(4);
            return Mono.deferContextual(context -> {
                assertThat(dropped).isTrue();
                assertThat(context.<String>get(LlmUserContext.KEY)).isEqualTo("owner");
                started.set(true);
                usage.accept(new DefaultUsage(10, 2));
                return Mono.just(new PlanStep(step.id(), "指导", ExpertType.RESUME, Map.of("action", "advice"), List.of()));
            });
        });
        var result = planner.prepare(AgentMode.AUTO, "帮我写简历建议", List.of(), ModelTier.FLASH,
                        usage -> tokens.addAndGet(usage.getTotalTokens()), Map.of())
                .contextWrite(c -> c.put(LlmUserContext.KEY, "owner").put(IntentPlanner.ON_EXPERT_ROUTE, (Runnable) () -> dropped.set(true)))
                .toFuture();
        assertThat(started).isTrue();
        assertThat(result).isNotDone();
        assertThat(tokens).hasValue(0);
        tail.tryEmitValue(chunk("\"plan\":null}"));
        assertThat(result.get(2, java.util.concurrent.TimeUnit.SECONDS).plan().steps().get(0).inputs())
                .containsEntry("action", "advice").containsKey("noteQuery");
        assertThat(tokens).hasValue(12);
        verify(resumes).prepare(any(), anyString(), anyList(), any(), any(), anyMap());
    }

    @Test void malformedTailDiscardsAndCancelsExpertWithoutTranscriptUsage() throws Exception {
        var gateway = mock(ModelGateway.class);
        var resumes = mock(ResumeIntentParser.class);
        var planner = planner(gateway, resumes);
        var cancelled = new AtomicBoolean();
        var tokens = new AtomicInteger();
        when(gateway.stream(any(), anyList(), eq(0.1)))
                .thenReturn(Flux.just(chunk(PREFIX), chunk("invalid")))
                .thenReturn(Flux.just(chunk("{\"kind\":\"chitchat\",\"confidence\":1}")));
        when(resumes.prepare(any(), anyString(), anyList(), any(), any(), anyMap())).thenAnswer(call -> {
            java.util.function.Consumer<org.springframework.ai.chat.metadata.Usage> usage = call.getArgument(4);
            usage.accept(new DefaultUsage(10, 2));
            return Mono.<PlanStep>never().doOnCancel(() -> cancelled.set(true));
        });
        assertThat(planner.prepare(AgentMode.AUTO, "讨论简历", List.of(), ModelTier.FLASH,
                usage -> tokens.addAndGet(usage.getTotalTokens()), Map.of()).block(Duration.ofSeconds(2)).kind()).isEqualTo("chitchat");
        assertThat(cancelled).isTrue();
        assertThat(tokens).hasValue(0);
    }

    @Test void cancelDuringPlannerTailCancelsExpertAndFlagOffWaitsForFullPlan() throws Exception {
        for (boolean enabled : List.of(true, false)) {
            var gateway = mock(ModelGateway.class);
            var resumes = mock(ResumeIntentParser.class);
            var planner = planner(gateway, resumes);
            planner.setEarlyExpertHandoff(enabled);
            var cancelled = new AtomicBoolean();
            when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(chunk(PREFIX)).concatWith(Flux.never()));
            when(resumes.prepare(any(), anyString(), anyList(), any(), any(), anyMap()))
                    .thenReturn(Mono.<PlanStep>never().doOnCancel(() -> cancelled.set(true)));
            var subscription = planner.prepare(AgentMode.AUTO, "讨论简历", List.of(), ModelTier.FLASH, ignored -> {}, Map.of()).subscribe();
            if (enabled) verify(resumes).prepare(any(), anyString(), anyList(), any(), any(), anyMap());
            else verifyNoInteractions(resumes);
            subscription.dispose();
            assertThat(cancelled.get()).isEqualTo(enabled);
        }
    }

    private IntentPlanner planner(ModelGateway gateway, ResumeIntentParser resumes) throws Exception {
        return new IntentPlanner(gateway, new ObjectMapper(), new ExpertRegistry(), new ByteArrayResource("planner".getBytes()), null, resumes);
    }
}
