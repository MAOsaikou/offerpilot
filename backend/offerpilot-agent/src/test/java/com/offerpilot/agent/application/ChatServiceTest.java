package com.offerpilot.agent.application;

import com.offerpilot.agent.event.AgentEvent;
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
    private ChatService service;

    @BeforeEach
    void setup() throws Exception {
        service = new ChatService(gateway, new ByteArrayResource("test system prompt".getBytes()));
        when(gateway.defaultTier()).thenReturn(ModelTier.CHEAP);
        when(gateway.modelName(any())).thenReturn("deepseek-v4-flash");
    }

    private ChatRequest request() {
        return new ChatRequest(null, "你好", null, null, new ChatRequest.Options(null, null, null));
    }

    private List<AgentEvent> events() {
        return service.chat(request()).collectList().block(Duration.ofSeconds(5));
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
        assertThat(((AgentEvent.ErrorEvent) events().getLast()).code()).isEqualTo(40001);
        when(gateway.stream(any(), anyList())).thenReturn(Flux.empty());
        assertThat(((AgentEvent.ErrorEvent) events().getLast()).code()).isEqualTo(40010);
    }

    @Test
    void cancellingDownstreamCancelsProvider() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(gateway.stream(any(), anyList())).thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
        var subscription = service.chat(request()).subscribe();
        subscription.dispose();
        assertThat(cancelled).isTrue();
    }
}
