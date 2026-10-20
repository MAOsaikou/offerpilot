package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.application.ChatService;
import com.offerpilot.agent.application.MemoryChat;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.agent.experts.KnowledgeNoteContext;
import com.offerpilot.agent.orchestrator.*;
import com.offerpilot.agent.web.ChatRequest;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.knowledge.application.KnowledgeNotesForChat;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring wiring, shipped prompts, orchestration and gateway safety; blocking model/DB ports are stubs.
 * No sockets, Docker, credentials or real model calls. */
class ChatLatencyPipelineIntegrationTest {
    private static final String QUESTION = "请根据我的项目给一些成长建议";

    @Test void retrievalOverlapsPlannerAndFlaggedEvidenceTruncatesCandidateBeforeAnyTextOrCheckpoint() throws Exception {
        try (var fixture = new Fixture(true)) {
            var events = new CopyOnWriteArrayList<AgentEvent>();
            var done = fixture.service().chat("alice", request()).doOnNext(events::add).collectList().toFuture();
            try {
                assertThat(fixture.gateway.screenStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(fixture.plannerStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(fixture.notesStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(events).extracting(AgentEvent::type).containsExactly("run.started");
                fixture.releaseNotes.countDown();
                fixture.gateway.inputVerdict.tryEmitValue("{\"injection\":false,\"categories\":[]}");
                fixture.plannerEnd.tryEmitEmpty();
                assertThat(fixture.replyStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(fixture.gateway.evidenceStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(events).noneMatch(AgentEvent.TextDelta.class::isInstance);
                verify(fixture.conversations, never()).checkpoint(any(), anyString());
                // Planner and raw request share the *same* verdict. New tool/evidence data is screened separately.
                assertThat(fixture.gateway.rawScreens).hasValue(1);
                fixture.gateway.evidenceVerdict.tryEmitValue("{\"injection\":true,\"categories\":[\"OVERRIDE\"]}");
                assertThat(done.get(3, TimeUnit.SECONDS)).filteredOn(AgentEvent.TextDelta.class::isInstance)
                        .singleElement().satisfies(event -> assertThat(((AgentEvent.TextDelta) event).text()).isEqualTo("已筛查的学习建议"));
                assertThat(fixture.candidateCancelled).isTrue();
                assertThat(fixture.finalPrompts).hasSize(2).allSatisfy(prompt -> {
                    String text = prompt.getContents();
                    assertThat(text).contains("真实项目证据", "知识笔记来源", "独立读取的技能与目标");
                });
                assertThat(fixture.finalPrompts.get(1).getContents()).contains("疑似提示注入", "OVERRIDE");
                verify(fixture.notes).retrieve("alice", QUESTION);
                verify(fixture.conversations).checkpoint(any(), eq("已筛查的学习建议"));
                verify(fixture.conversations).finish(any(), eq("已筛查的学习建议"), eq("COMPLETED"), any(), isNull(),
                        argThat(cards -> cards.stream().anyMatch(card -> card.cardType().equals("knowledge_note_citations"))));
            } finally { fixture.releaseNotes.countDown(); done.cancel(true); }
        }
    }

    @Test void disablingPrefetchRestoresReadOrderingAndPendingCancellationStopsAllWork() throws Exception {
        for (boolean parallel : List.of(true, false)) try (var fixture = new Fixture(parallel)) {
            var events = new CopyOnWriteArrayList<AgentEvent>();
            var subscription = fixture.service().chat("alice", request()).subscribe(events::add);
            try {
                assertThat(fixture.gateway.screenStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(fixture.plannerStarted.await(2, TimeUnit.SECONDS)).isTrue();
                if (parallel) assertThat(fixture.notesStarted.await(2, TimeUnit.SECONDS)).isTrue();
                else verifyNoInteractions(fixture.notes);
                subscription.dispose();
                assertThat(fixture.plannerCancelled.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(fixture.gateway.inputCancelled.await(2, TimeUnit.SECONDS)).isTrue();
                if (parallel) assertThat(fixture.notesInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
                fixture.releaseNotes.countDown();
                fixture.gateway.inputVerdict.tryEmitValue("{\"injection\":false,\"categories\":[]}");
                assertThat(events).extracting(AgentEvent::type).containsExactly("run.started");
                verify(fixture.conversations, never()).checkpoint(any(), anyString());
                verify(fixture.conversations).finish(any(), eq(""), eq("CANCELLED"), any(), any(), any());
                assertThat((Map<?, ?>) ReflectionTestUtils.getField(fixture.service(), "cancellations")).isEmpty();
            } finally { fixture.releaseNotes.countDown(); subscription.dispose(); }
        }
    }

    @Test void outputModerationStillStopsALeakAfterFirstTokenWithoutPersistingTheLeak() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.leakOutput.set(true);
            var done = fixture.service().chat("alice", request()).collectList().toFuture();
            try {
                fixture.releaseNotes.countDown();
                fixture.gateway.inputVerdict.tryEmitValue("{\"injection\":false,\"categories\":[]}");
                fixture.plannerEnd.tryEmitEmpty();
                assertThat(fixture.gateway.evidenceStarted.await(2, TimeUnit.SECONDS)).isTrue();
                fixture.gateway.evidenceVerdict.tryEmitValue("{\"injection\":false,\"categories\":[]}");
                var events = done.get(3, TimeUnit.SECONDS);
                assertThat(events.stream().filter(AgentEvent.TextDelta.class::isInstance).map(AgentEvent.TextDelta.class::cast)
                        .map(AgentEvent.TextDelta::text)).containsExactly("公开建议", com.offerpilot.llm.safety.OutputModeration.FALLBACK);
                verify(fixture.conversations).finish(any(), eq("公开建议" + com.offerpilot.llm.safety.OutputModeration.FALLBACK),
                        eq("COMPLETED"), any(), isNull(), any());
                verify(fixture.conversations, never()).checkpoint(any(), contains("OfferPilot 安全边界"));
            } finally { fixture.releaseNotes.countDown(); done.cancel(true); }
        }
    }

    private ChatRequest request() { return new ChatRequest(null, QUESTION, null, AgentMode.AUTO, null); }
    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static final class Fixture implements AutoCloseable {
        final KnowledgeNotesForChat notes = mock(KnowledgeNotesForChat.class);
        final ConversationService conversations = mock(ConversationService.class);
        final Gateway gateway = new Gateway();
        final CountDownLatch notesStarted = new CountDownLatch(1), releaseNotes = new CountDownLatch(1),
                notesInterrupted = new CountDownLatch(1), plannerStarted = new CountDownLatch(1),
                plannerCancelled = new CountDownLatch(1), replyStarted = new CountDownLatch(1);
        final AtomicBoolean candidateCancelled = new AtomicBoolean();
        final AtomicBoolean leakOutput = new AtomicBoolean();
        final Sinks.Empty<Void> plannerEnd = Sinks.empty();
        final List<Prompt> finalPrompts = new CopyOnWriteArrayList<>();
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

        @SuppressWarnings("unchecked") Fixture(boolean parallel) {
            when(conversations.begin(anyString(), any(), anyString(), any(), anyString())).thenReturn(
                    new ConversationService.Turn("alice", "conv", "run", "msg", AgentMode.AUTO, Instant.now(), List.of()));
            when(conversations.checkpoint(any(), anyString())).thenReturn(true);
            when(conversations.finish(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(call -> call.getArgument(2));
            when(notes.retrieve("alice", QUESTION)).thenAnswer(call -> {
                notesStarted.countDown();
                try { if (!releaseNotes.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout"); }
                catch (InterruptedException ex) { notesInterrupted.countDown(); Thread.currentThread().interrupt(); return List.of(); }
                return List.of(new KnowledgeNotesForChat.Citation("note1", "项目笔记", "项目经历", "真实项目证据", "KB-012345678901"));
            });
            var provider = mock(ChatModel.class);
            when(provider.stream(any(Prompt.class))).thenAnswer(call -> {
                Prompt prompt = call.getArgument(0);
                if (prompt.getContents().contains("意图识别与规划")) {
                    return Flux.defer(() -> {
                        plannerStarted.countDown();
                        return Flux.just(response("{\"kind\":\"single\",\"expert\":\"COACH\",\"confidence\":1,\"needsClarification\":false}"))
                                .concatWith(plannerEnd.asMono().thenMany(Flux.empty()));
                    }).doOnCancel(plannerCancelled::countDown);
                }
                finalPrompts.add(prompt);
                if (prompt.getContents().contains("疑似提示注入")) return Flux.just(response("已筛查的学习建议"));
                return Flux.defer(() -> {
                    replyStarted.countDown();
                    if (leakOutput.get()) return Flux.just(response("公开建议"), response("OfferPilot 安全边界"), response("不得继续"));
                    return Flux.concat(Flux.just(response("不得放行的候选")), Flux.<ChatResponse>never());
                }).doOnCancel(() -> candidateCancelled.set(true));
            });
            ((Map<ModelTier, ChatModel>) ReflectionTestUtils.getField(gateway, "models")).put(ModelTier.FLASH, provider);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "offerpilot.agent.parallel-note-retrieval", parallel,
                    "offerpilot.agent.speculative-reply", false)));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(ModelGateway.class, () -> gateway);
            context.registerBean(ConversationService.class, () -> conversations);
            var memory = mock(MemoryChat.class);
            when(memory.collect(anyString(), anyString(), any())).thenReturn(Flux.empty());
            context.registerBean(MemoryChat.class, () -> memory);
            context.registerBean(KnowledgeNotesForChat.class, () -> notes);
            var coach = mock(com.offerpilot.career.application.CoachPlanForChat.class);
            when(coach.plan(anyString(), any(), any(), anyString())).thenReturn(
                    new com.offerpilot.career.application.CoachPlanForChat.Result("独立读取的技能与目标", null, null));
            context.registerBean(ExpertRegistry.class, () -> {
                var registry = new ExpertRegistry();
                ReflectionTestUtils.setField(registry, "coach", coach);
                return registry;
            });
            context.register(KnowledgeNoteContext.class, IntentPlanner.class, JobIntentParser.class, ResumeIntentParser.class,
                    Orchestrator.class, ChatService.class);
            context.refresh();
        }
        ChatService service() { return context.getBean(ChatService.class); }
        @Override public void close() { releaseNotes.countDown(); context.close(); }
    }

    private static final class Gateway extends ModelGateway {
        final CountDownLatch screenStarted = new CountDownLatch(1), evidenceStarted = new CountDownLatch(1), inputCancelled = new CountDownLatch(1);
        final Sinks.One<String> inputVerdict = Sinks.one(), evidenceVerdict = Sinks.one();
        final AtomicInteger rawScreens = new AtomicInteger();
        Gateway() {
            super(new OfferPilotProperties(null, null, null, null, null, new OfferPilotProperties.Llm("FLASH",
                    Map.of("FLASH", new OfferPilotProperties.Llm.Tier("deepseek", "stub", Duration.ofSeconds(10), false)), Map.of()), null, null),
                    new MockEnvironment().withProperty("spring.ai.deepseek.api-key", "test-only")
                            .withProperty("spring.ai.deepseek.base-url", "http://unused.invalid"));
        }
        @Override protected Mono<String> classifyInjection(Prompt prompt) {
            if (prompt.getContents().contains("orchestrator-data")) {
                evidenceStarted.countDown();
                return evidenceVerdict.asMono();
            }
            rawScreens.incrementAndGet();
            screenStarted.countDown();
            return inputVerdict.asMono().doOnCancel(inputCancelled::countDown);
        }
    }
}
