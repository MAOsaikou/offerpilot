package com.offerpilot.agent.application;

import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.agent.orchestrator.IntentPlanner;
import com.offerpilot.agent.orchestrator.Orchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.conversation.application.MessageCard;
import org.mockito.ArgumentCaptor;
import com.offerpilot.agent.web.ChatRequest;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatServiceTest {
    private final ModelGateway gateway = mock(ModelGateway.class);
    private final com.offerpilot.conversation.application.ConversationService conversations = mock(com.offerpilot.conversation.application.ConversationService.class);
    private ChatService service;
    private Orchestrator orchestrator;

    @BeforeEach
    void setup() throws Exception {
        var experts = new ExpertRegistry();
        orchestrator = new Orchestrator(new IntentPlanner(gateway, new ObjectMapper(), experts,
                new ByteArrayResource("planner".getBytes())), experts);
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.just(new ChatResponse(List.of(
                new Generation(new AssistantMessage("{\"kind\":\"chitchat\",\"confidence\":1}"))))));
        var json = new ObjectMapper();
        service = new ChatService(gateway, conversations, new ByteArrayResource("test system prompt".getBytes()), orchestrator, json);
        when(conversations.begin(anyString(), any(), anyString(), any(), anyString())).thenReturn(
                new com.offerpilot.conversation.application.ConversationService.Turn("owner", "conv", "run", "msg",
                        com.offerpilot.agent.domain.AgentMode.AUTO, java.time.Instant.now(), List.of()));
        when(conversations.checkpoint(any(), anyString())).thenReturn(true);
        when(conversations.finish(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(gateway.defaultTier()).thenReturn(ModelTier.CHEAP);
        when(gateway.modelName(any())).thenReturn("deepseek-v4-flash");
    }

    private ChatRequest request() {
        return new ChatRequest(null, "你好", null, null, new ChatRequest.Options(null, null, null));
    }

    private List<AgentEvent> events() {
        return service.chat("owner", request()).collectList().block(Duration.ofSeconds(5));
    }

    @Test
    void streamsTextThenUsageThenCompletionWithIncreasingIds() {
        when(gateway.stream(eq(ModelTier.CHEAP), anyList())).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("你好")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("，同学"))),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(12, 6)).build())));
        var events = events();
        assertThat(events).extracting(AgentEvent::type)
                .containsExactly("run.started", "text.delta", "text.delta", "usage", "run.completed");
        assertThat(events).extracting(AgentEvent::id).containsExactly(1, 2, 3, 4, 5);
        assertThat((AgentEvent.Usage) events.get(3)).extracting(AgentEvent.Usage::promptTokens,
                AgentEvent.Usage::completionTokens).containsExactly(12, 6);
        verify(gateway).stream(eq(ModelTier.CHEAP), argThat(messages -> messages.size() == 2
                && messages.get(0).getText().equals("test system prompt") && messages.get(1).getText().equals("你好")));
    }

    @Test
    void systemPromptCarriesCurrentDate() throws Exception {
        var dated = new ChatService(gateway, conversations, new ByteArrayResource(
                "今天是 {current_date}".getBytes(java.nio.charset.StandardCharsets.UTF_8)), orchestrator, new ObjectMapper());
        when(gateway.stream(eq(ModelTier.CHEAP), anyList())).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("好"))))));

        dated.chat("owner", request()).collectList().block(Duration.ofSeconds(5));

        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        verify(gateway).stream(eq(ModelTier.CHEAP),
                argThat(messages -> messages.get(0).getText().equals("今天是 " + today)));
    }

    @Test
    void missingKeyProducesActionableTerminalError() {
        when(gateway.stream(any(), anyList())).thenThrow(new BusinessException(ErrorCode.MODEL_NOT_CONFIGURED));
        assertThat(events()).last().isInstanceOfSatisfying(AgentEvent.ErrorEvent.class, e -> {
            assertThat(e.code()).isEqualTo(40009);
            assertThat(e.recoverable()).isFalse();
        });
    }

    @Test
    void providerErrorAfterPartialOutputNeverLeaksDetailsOrReportsSuccess() {
        when(gateway.stream(any(), anyList())).thenReturn(Flux.concat(
                Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("部分回复"))))),
                Flux.error(new RuntimeException("secret-key private provider body"))));
        var result = events();
        assertThat(result).extracting(AgentEvent::type).containsExactly("run.started", "text.delta", "error");
        assertThat(result.toString()).doesNotContain("secret-key", "private provider body");
    }

    @Test
    void timeoutAndEmptyResponseAreFailures() {
        when(gateway.stream(any(), anyList())).thenReturn(Flux.error(new TimeoutException()));
        assertThat(((AgentEvent.ErrorEvent) events().get(events().size() - 1)).code()).isEqualTo(40001);
        when(gateway.stream(any(), anyList())).thenReturn(Flux.empty());
        assertThat(((AgentEvent.ErrorEvent) events().get(events().size() - 1)).code()).isEqualTo(40010);
    }

    @Test
    void cancellingDownstreamCancelsProvider() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(gateway.stream(any(), anyList())).thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
        var subscription = service.chat("owner", request()).subscribe();
        subscription.dispose();
        assertThat(cancelled).isTrue();
    }

    @Test
    void cancellationDuringPlanningStopsProviderAndCompletesAsCancelled() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(gateway.stream(any(), anyList(), eq(0.1))).thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
        when(conversations.finish(any(), anyString(), anyString(), any(), any(), any())).thenReturn("CANCELLED");
        var received = new java.util.concurrent.CopyOnWriteArrayList<AgentEvent>();
        service.chat("owner", request()).subscribe(received::add);
        service.cancel("owner", "run");
        assertThat(cancelled).isTrue();
        assertThat(received).extracting(AgentEvent::type).containsExactly("run.started", "run.completed");
        assertThat(((AgentEvent.RunCompleted)received.get(1)).status()).isEqualTo("CANCELLED");
        verify(gateway, never()).stream(any(), anyList());
    }

    @Test
    void acceptedCardsAreStreamedAndSavedAndInvalidCardsAreDropped() throws Exception {
        Orchestrator cards = mock(Orchestrator.class);
        when(cards.run(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Flux.just(
                new AgentEvent.UiCard("ui.card", 0, "run", java.time.Instant.now(), "s1", "card_job_1", "job_match", java.util.Map.of("score", 80)),
                new AgentEvent.UiCard("ui.card", 0, "run", java.time.Instant.now(), "s1", "card_job_1", "job_match", java.util.Map.of("score", 1)),
                new AgentEvent.UiCard("ui.card", 0, "run", java.time.Instant.now(), "s1", "card_bad", "not_a_card", java.util.Map.of("score", 1))));
        var local = new ChatService(gateway, conversations, new ByteArrayResource("test system prompt".getBytes()), cards, new ObjectMapper());
        when(gateway.stream(eq(ModelTier.CHEAP), anyList())).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("报告"))))));

        var events = local.chat("owner", request()).collectList().block(Duration.ofSeconds(5));

        assertThat(events).extracting(AgentEvent::type).containsExactly("run.started", "ui.card", "text.delta", "run.completed");
        assertThat(((AgentEvent.UiCard) events.get(1)).cardId()).isEqualTo("card_job_1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageCard>> saved = ArgumentCaptor.forClass(List.class);
        verify(conversations).finish(any(), eq("报告"), eq("COMPLETED"), isNull(), isNull(), saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(card -> {
            assertThat(card.cardId()).isEqualTo("card_job_1");
            assertThat(card.cardType()).isEqualTo("job_match");
            assertThat(card.payload().path("score").asInt()).isEqualTo(80);
        });
    }
}
