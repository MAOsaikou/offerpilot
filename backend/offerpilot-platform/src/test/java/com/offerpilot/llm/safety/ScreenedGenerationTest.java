package com.offerpilot.llm.safety;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class ScreenedGenerationTest {
    private ChatResponse chunk(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }

    @Test void injectionCancelsRunningCandidateBeforeReplacementAndLeaksNothing() {
        var cancelled = new AtomicBoolean();
        var ready = Sinks.<Prompt>one();
        var candidate = Flux.concat(Flux.just(chunk("unsafe candidate")), Flux.<ChatResponse>never())
                .doOnCancel(() -> cancelled.set(true));
        StepVerifier.create(ScreenedGeneration.release(ready.asMono(), candidate, ignored -> false, prompt -> {
                    assertThat(cancelled).isTrue();
                    return Flux.just(chunk("safe replacement"));
                }))
                .then(() -> ready.tryEmitValue(new Prompt("screened")))
                .expectNextMatches(response -> response.getResult().getOutput().getText().equals("safe replacement"))
                .verifyComplete();
    }

    @Test void pendingChunkLimitDoesNotTruncateAnAlreadyApprovedLongStream() {
        var ready = Sinks.<Prompt>one();
        var source = Sinks.many().unicast().<ChatResponse>onBackpressureBuffer();
        StepVerifier.create(ScreenedGeneration.release(ready.asMono(), source.asFlux(), ignored -> true, ignored -> Flux.empty()))
                .then(() -> { source.tryEmitNext(chunk("initial")); ready.tryEmitValue(new Prompt("clean")); })
                .expectNextCount(1)
                .then(() -> {
                    for (int i = 0; i < 8200; i++) source.tryEmitNext(chunk("x"));
                    source.tryEmitComplete();
                }).expectNextCount(8200).verifyComplete();
    }

    @Test void pendingErrorsStayBehindVerdictAndCleanCancellationCancelsAdoptedStream() {
        var ready = Sinks.<Prompt>one();
        var error = new IllegalStateException("provider unavailable");
        StepVerifier.create(ScreenedGeneration.release(ready.asMono(), Flux.error(error), ignored -> true, ignored -> Flux.empty()))
                .then(() -> ready.tryEmitValue(new Prompt("clean"))).expectErrorMatches(found -> found == error).verify();
        var cancelled = new AtomicBoolean();
        StepVerifier.create(ScreenedGeneration.release(reactor.core.publisher.Mono.just(new Prompt("clean")),
                        Flux.concat(Flux.just(chunk("ok")), Flux.<ChatResponse>never()).doOnCancel(() -> cancelled.set(true)),
                        ignored -> true, ignored -> Flux.empty()))
                .expectNextCount(1).thenCancel().verify();
        assertThat(cancelled).isTrue();
    }
}
