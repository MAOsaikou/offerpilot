package com.offerpilot.llm.safety;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

/** Subscription-owned speculation: no chunk (including errors) escapes before the verdict. */
public final class ScreenedGeneration {
    private ScreenedGeneration() {}

    public static Flux<ChatResponse> release(Mono<Prompt> verdict, Flux<ChatResponse> candidate,
            Predicate<Prompt> unchanged, Function<Prompt, Flux<ChatResponse>> screened) {
        return Flux.deferContextual(context -> Flux.using(() -> {
            var waiting = new AtomicBoolean(true);
            var chunks = new AtomicInteger();
            var characters = new AtomicInteger();
            // Bound retained speculative output without dropping the beginning of a reply.
            var replay = candidate.<ChatResponse>handle((chunk, sink) -> {
                String text = chunk.getResult() == null || chunk.getResult().getOutput() == null
                        ? null : chunk.getResult().getOutput().getText();
                if (waiting.get() && (chunks.incrementAndGet() > 8192
                        || characters.addAndGet(text == null ? 0 : text.length()) > 256000))
                    sink.error(new IllegalStateException("Pending generation limit"));
                else sink.next(chunk);
            }).contextWrite(context).replay();
            return new Pending(replay, replay.connect(), waiting);
        }, pending -> verdict.flatMapMany(prompt -> {
            if (unchanged.test(prompt)) {
                pending.waiting().set(false);
                return pending.output();
            }
            // Existing blocking behavior is a category warning, not an error/refusal.
            pending.close();
            return screened.apply(prompt);
        }), Pending::close));
    }

    private record Pending(Flux<ChatResponse> output, Disposable connection, AtomicBoolean waiting) {
        void close() { connection.dispose(); }
    }
}
