package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.agent.orchestrator.IntentPlanner;
import com.offerpilot.agent.orchestrator.Orchestrator;
import com.offerpilot.agent.web.ChatRequest;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.conversation.application.ConversationService;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatParallelEffectsTest {
    @Test void directAdviceToolAndReplyWaitForCleanAndCancelLeavesNoRun() throws Exception {
        for (boolean cancel : List.of(false, true)) {
            var gateway = new Gateway();
            var experts = spy(new ExpertRegistry());
            var conversations = conversations();
            var service = service(gateway, experts, conversations, null);
            var events = new java.util.concurrent.CopyOnWriteArrayList<AgentEvent>();
            var completed = new CountDownLatch(1);
            service.chat("owner", new ChatRequest(null, "简历里的项目经历怎么写", null, AgentMode.AUTO, null))
                    .subscribe(events::add, ignored -> completed.countDown(), completed::countDown);
            assertThat(gateway.started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(events).extracting(AgentEvent::type).containsExactly("run.started");
            verify(experts, never()).execute(anyString(), anyMap(), anyString(), any(), anyMap());
            assertThat(gateway.replyCalls.get()).isZero();
            if (cancel) service.cancel("owner", "run");
            gateway.verdict.tryEmitValue("{\"injection\":false,\"categories\":[]}");
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
            if (cancel) {
                assertThat(events).extracting(AgentEvent::type).containsExactly("run.started", "run.completed");
                assertThat(gateway.replyCalls.get()).isZero();
            } else {
                assertThat(events).extracting(AgentEvent::type).contains("tool.call", "text.delta", "run.completed");
                verify(experts).execute(eq("owner"), anyMap(), eq("run"), any(), anyMap());
            }
            @SuppressWarnings("unchecked") Map<String, ?> leftover = (Map<String, ?>) org.springframework.test.util.ReflectionTestUtils.getField(service, "cancellations");
            assertThat(leftover).isEmpty();
        }
    }

    @Test void explicitMemoryDoesNotRequestApprovalOrWriteBeforeSafetyAndCancellation() throws Exception {
        var gateway = new Gateway();
        var memory = mock(MemoryChat.class);
        when(memory.enabled("owner")).thenReturn(true);
        var candidate = com.offerpilot.memory.application.MemoryCandidate.extract("记住：我想在上海找工作");
        assertThat(candidate).isNotNull();
        when(memory.candidate(eq("owner"), anyString())).thenReturn(candidate);
        var service = service(gateway, new ExpertRegistry(), conversations(), memory);
        var events = new java.util.concurrent.CopyOnWriteArrayList<AgentEvent>();
        var completed = new CountDownLatch(1);
        service.chat("owner", new ChatRequest(null, "记住：我想在上海找工作", null, null, null))
                .subscribe(events::add, ignored -> completed.countDown(), completed::countDown);
        assertThat(gateway.started.await(2, TimeUnit.SECONDS)).isTrue();
        verify(memory, never()).write(anyString(), anyString(), any(), any());
        service.cancel("owner", "run");
        gateway.verdict.tryEmitValue("{\"injection\":false,\"categories\":[]}");
        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        verify(memory, never()).write(anyString(), anyString(), any(), any());
        assertThat(events).extracting(AgentEvent::type).containsExactly("run.started", "run.completed");
        @SuppressWarnings("unchecked") Map<String, ?> leftover = (Map<String, ?>) org.springframework.test.util.ReflectionTestUtils.getField(service, "cancellations");
        assertThat(leftover).isEmpty();
    }

    private ChatService service(Gateway gateway, ExpertRegistry experts, ConversationService conversations, MemoryChat memory) throws Exception {
        var planner = new IntentPlanner(gateway, new ObjectMapper(), experts, new ByteArrayResource("planner".getBytes()));
        var service = new ChatService(gateway, conversations, new ByteArrayResource("reply".getBytes()),
                new Orchestrator(planner, experts), new ObjectMapper(), memory);
        service.setParallelSafetyScreen(true);
        return service;
    }

    private ConversationService conversations() {
        var conversations = mock(ConversationService.class);
        when(conversations.begin(anyString(), any(), anyString(), any(), anyString())).thenReturn(
                new ConversationService.Turn("owner", "conv", "run", "msg", AgentMode.AUTO, Instant.now(), List.of()));
        when(conversations.checkpoint(any(), anyString())).thenReturn(true);
        when(conversations.finish(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(call -> call.getArgument(2));
        return conversations;
    }

    private static class Gateway extends ModelGateway {
        final Sinks.One<String> verdict = Sinks.one();
        final CountDownLatch started = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger replyCalls = new java.util.concurrent.atomic.AtomicInteger();
        Gateway() {
            super(new OfferPilotProperties(null, null, null, null, null, new OfferPilotProperties.Llm("FLASH",
                    Map.of("FLASH", new OfferPilotProperties.Llm.Tier("custom", "stub", Duration.ofSeconds(5), true)), Map.of()), null, null),
                    new MockEnvironment());
        }
        @Override protected Mono<String> classifyInjection(Prompt prompt) { started.countDown(); return verdict.asMono(); }
        // Isolate side-effect sequencing from provider transport (covered by ParallelSafetyScreenTest).
        @Override public Flux<ChatResponse> stream(ModelTier tier, List<Message> prompt) {
            replyCalls.incrementAndGet();
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("真实经历写清职责和结果。")))));
        }
    }
}
