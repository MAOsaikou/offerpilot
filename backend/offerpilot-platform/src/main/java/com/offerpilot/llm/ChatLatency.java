package com.offerpilot.llm;

import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Supplier;

/** Request-local wall-clock trace. Never records prompt text, owners, evidence or credentials. */
public final class ChatLatency {
    public static final String KEY = ChatLatency.class.getName();
    private final String trace = UUID.randomUUID().toString();
    private final long started = System.nanoTime();
    private volatile String run = "pending";

    public void bind(String runId) { run = runId; }

    public <T> T measure(String phase, Supplier<T> action) {
        long begin = System.nanoTime();
        String signal = "complete";
        try { return action.get(); }
        catch (RuntimeException | Error ex) { signal = "error"; throw ex; }
        finally { record(phase, begin, signal); }
    }

    public void mark(String phase) { record(phase, started, "mark"); }

    private void record(String phase, long begin, Object signal) {
        long now = System.nanoTime();
        LoggerFactory.getLogger(ChatLatency.class).debug(
                "AG02 trace={} run={} phase={} startMs={} elapsedMs={} sinceRequestMs={} signal={}",
                trace, run, phase, (begin - started) / 1_000_000, (now - begin) / 1_000_000,
                (now - started) / 1_000_000, signal);
    }

    public static <T> Mono<T> mono(String phase, Mono<T> source) {
        return Mono.deferContextual(context -> {
            ChatLatency timing = context.getOrDefault(KEY, null);
            if (timing == null) return source;
            long begin = System.nanoTime();
            return source.doFinally(signal -> timing.record(phase, begin, signal));
        });
    }

    public static <T> Flux<T> flux(String phase, Flux<T> source) {
        return Flux.deferContextual(context -> {
            ChatLatency timing = context.getOrDefault(KEY, null);
            if (timing == null) return source;
            long begin = System.nanoTime();
            return source.doFinally(signal -> timing.record(phase, begin, signal));
        });
    }
}
