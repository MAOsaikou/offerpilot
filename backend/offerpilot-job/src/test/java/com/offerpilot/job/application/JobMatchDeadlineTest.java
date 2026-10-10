package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import com.offerpilot.llm.quota.LlmQuotaException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobMatchDeadlineTest {
    final ObjectMapper json = new ObjectMapper();
    final ModelGateway gateway = mock(ModelGateway.class);
    final VirtualTimeScheduler clock = VirtualTimeScheduler.create();
    final String valid = """
        {"summary":"项目依据","requirements":[{"requirement":"Java","status":"MET","jdQuote":"熟悉Java",
        "resumePath":"/summary","resumeQuote":"Java项目","reason":"原文证据","suggestion":"准备细节"}]}
        """;

    JobMatcher matcher() throws Exception {
        when(gateway.timeout(ModelTier.PRO)).thenReturn(Duration.ofSeconds(180));
        when(gateway.timeout(ModelTier.FLASH)).thenReturn(Duration.ofSeconds(60));
        when(gateway.modelName(any())).thenReturn("stub");
        return new JobMatcher(gateway, json, new ByteArrayResource("rules".getBytes()));
    }
    ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
    reactor.core.publisher.Mono<com.fasterxml.jackson.databind.JsonNode> analyze(JobMatcher matcher, ModelTier tier) throws Exception {
        return matcher.analyze(json.readTree("{\"requirement\":\"熟悉Java\"}"),
                json.readTree("{\"summary\":\"Java项目\"}"), tier, clock);
    }

    @Test void proCanCompleteAfterOldNinetySecondDeadline() throws Exception {
        var matcher = matcher();
        when(gateway.stream(eq(ModelTier.PRO), anyList(), eq(0.0)))
                .thenReturn(Flux.just(response(valid)).delaySubscription(Duration.ofSeconds(125), clock));
        var result = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var error = new AtomicReference<Throwable>();
        analyze(matcher, ModelTier.PRO).subscribe(result::set, error::set);
        clock.advanceTimeBy(Duration.ofSeconds(91));
        assertThat(result).hasNullValue(); assertThat(error).hasNullValue();
        clock.advanceTimeBy(Duration.ofSeconds(34));
        assertThat(error).hasNullValue();
        assertThat(result.get().path("score").asInt()).isEqualTo(100);
        assertThat(result.get().path("modelTier").asText()).isEqualTo("PRO");
        verify(gateway, times(1)).stream(eq(ModelTier.PRO), anyList(), eq(0.0));
    }

    @Test void repairUsesRemainingBudgetAndCancelsWithoutThirdCall() throws Exception {
        var matcher = matcher();
        var cancelled = new AtomicBoolean();
        when(gateway.stream(eq(ModelTier.PRO), anyList(), eq(0.0)))
                .thenReturn(Flux.just(response("invalid JSON")).delaySubscription(Duration.ofSeconds(125), clock),
                        Flux.just(response(valid)).delaySubscription(Duration.ofSeconds(80), clock).doOnCancel(() -> cancelled.set(true)));
        var error = new AtomicReference<Throwable>();
        analyze(matcher, ModelTier.PRO).subscribe(value -> fail("late repair must not succeed"), error::set);
        clock.advanceTimeBy(Duration.ofSeconds(179));
        assertThat(error).hasNullValue();
        clock.advanceTimeBy(Duration.ofSeconds(1));
        assertTimeout(error.get(), "180");
        assertThat(cancelled).isTrue();
        clock.advanceTimeBy(Duration.ofDays(1));
        verify(gateway, times(2)).stream(eq(ModelTier.PRO), anyList(), eq(0.0));
    }

    @Test void ongoingChunksCannotExtendFlashTotalDeadline() throws Exception {
        var matcher = matcher(); var cancelled = new AtomicBoolean();
        when(gateway.stream(eq(ModelTier.FLASH), anyList(), eq(0.0)))
                .thenReturn(Flux.interval(Duration.ofSeconds(10), clock).map(i -> response(" "))
                        .doOnCancel(() -> cancelled.set(true)));
        var error = new AtomicReference<Throwable>();
        analyze(matcher, ModelTier.FLASH).subscribe(value -> fail("must time out"), error::set);
        clock.advanceTimeBy(Duration.ofSeconds(60));
        assertTimeout(error.get(), "60"); assertThat(cancelled).isTrue();
        verify(gateway, times(1)).stream(eq(ModelTier.FLASH), anyList(), eq(0.0));
    }

    @Test void quotaAndProviderFailuresNeverTriggerStructuredRepair() throws Exception {
        var matcher = matcher();
        var quota = new LlmQuotaException(ErrorCode.LLM_RATE_LIMITED, 30);
        for (RuntimeException original : List.of(quota, new RuntimeException(new java.net.SocketTimeoutException()))) {
            when(gateway.stream(eq(ModelTier.PRO), anyList(), eq(0.0))).thenReturn(Flux.error(original));
            var error = new AtomicReference<Throwable>();
            analyze(matcher, ModelTier.PRO).subscribe(value -> fail("must fail"), error::set);
            if (original == quota) assertThat(error.get()).isSameAs(quota);
            else assertTimeout(error.get(), "180");
        }
        verify(gateway, times(2)).stream(eq(ModelTier.PRO), anyList(), eq(0.0));
    }

    private void assertTimeout(Throwable error, String seconds) {
        assertThat(error).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) error).errorCode()).isEqualTo(ErrorCode.MODEL_TIMEOUT);
        assertThat(error.getMessage()).contains(seconds, "未保存报告", "重试");
    }
}
