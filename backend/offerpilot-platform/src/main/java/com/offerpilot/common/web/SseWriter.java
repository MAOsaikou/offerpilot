package com.offerpilot.common.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import org.reactivestreams.Publisher;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One serialized write/termination lane per response, using the emitter's monitor so MVC
 * initialization shares the same lock. Never complete twice or dispatch an application error
 * through MVC after opening an SSE stream. Early sends/completion are buffered by Spring.
 */
public final class SseWriter {
    private final ObjectMapper json;
    private final SseEmitter emitter;
    private final String failureMessage;
    private final Runnable onDisconnect;
    private final reactor.core.Disposable.Swap subscription = Disposables.swap();
    private final reactor.core.Disposable.Swap heartbeat = Disposables.swap();
    private final reactor.core.Disposable.Composite resources = Disposables.composite(subscription, heartbeat);
    // All mutable state and emitter access are guarded by the emitter monitor.
    private boolean stopped;
    private boolean handlingFailure;
    private int sequence;
    private String runId;

    SseWriter(ObjectMapper json, SseEmitter emitter, Duration interval, String runId,
              String failureMessage, Runnable onDisconnect) {
        this.json = json;
        this.emitter = emitter;
        this.runId = runId;
        this.failureMessage = failureMessage;
        this.onDisconnect = onDisconnect;
        emitter.onCompletion(this::disconnected);
        emitter.onTimeout(this::disconnected);
        emitter.onError(ignored -> disconnected());
        heartbeat.update(Flux.interval(interval).onBackpressureDrop().publishOn(Schedulers.boundedElastic(), 1)
                .subscribe(ignored -> ping(), ignored -> disconnected()));
    }

    public SseEmitter emitter() { return emitter; }

    /** Swap disposes even a subscription installed after synchronous completion/disconnect. */
    public <T> void subscribe(Publisher<T> source, Consumer<T> onNext) {
        synchronized (emitter) {
            if (stopped) return;
        }
        try {
            subscription.update(Flux.from(source).subscribeOn(Schedulers.boundedElastic())
                    .publishOn(Schedulers.boundedElastic(), 1).subscribe(onNext, this::fail, this::complete));
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    /** Pass an existing domain envelope through without changing fields or event ids. */
    public void send(int id, String type, String eventRunId, Object event, boolean terminal) {
        synchronized (emitter) {
            if (stopped) return;
            sequence = id;
            runId = eventRunId;
            write(id, type, event);
            if (terminal) complete();
        }
    }

    /** The two standalone resume streams use the same existing envelope and sequence. */
    public void send(String type, Map<String, Object> fields) {
        synchronized (emitter) {
            if (stopped) return;
            int id = ++sequence;
            var event = new LinkedHashMap<String, Object>(fields);
            event.put("type", type);
            event.put("id", id);
            event.put("runId", runId);
            event.put("ts", Instant.now().toString());
            write(id, type, event);
            if (type.equals("run.completed") || type.equals("error")) complete();
        }
    }

    private void write(int id, String type, Object event) {
        String data;
        try {
            data = json.writeValueAsString(event);
        } catch (JsonProcessingException error) {
            if (handlingFailure) complete();
            else fail(error);
            return;
        }
        try {
            emitter.send(SseEmitter.event().id(String.valueOf(id)).name(type).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | RuntimeException transportFailure) {
            // Spring/container owns IO error dispatch. Do not flush or write another event to a broken response.
            stop(true);
        }
    }

    private void ping() {
        synchronized (emitter) {
            if (stopped) return;
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | RuntimeException transportFailure) {
                stop(true);
            }
        }
    }

    public void fail(Throwable error) {
        synchronized (emitter) {
            if (stopped) return;
            handlingFailure = true;
            // No provider bodies/exception text for unknown errors.
            send("error", Map.of("code", error instanceof BusinessException b ? b.errorCode().code() : 50000,
                    "message", error instanceof BusinessException b ? b.getMessage() : failureMessage,
                    "recoverable", false));
            complete();
        }
    }

    public void complete() {
        synchronized (emitter) {
            if (stopped) return;
            stop(false); // Stop heartbeat immediately, including before MVC initialization (no callbacks in that case).
            try {
                emitter.complete();
            } catch (RuntimeException closedResponse) {
                // A concurrent container disconnect may already have recycled the response.
            }
        }
    }

    private void disconnected() {
        synchronized (emitter) {
            stop(true);
        }
    }

    private void stop(boolean disconnected) {
        if (stopped) return;
        stopped = true; // Set before cancellation, which can synchronously reenter error/completion callbacks.
        try {
            resources.dispose();
        } catch (RuntimeException cleanupFailure) {
            LoggerFactory.getLogger(SseWriter.class).warn("Unable to dispose SSE subscription");
        }
        if (disconnected) {
            try {
                onDisconnect.run();
            } catch (RuntimeException cleanupFailure) {
                LoggerFactory.getLogger(SseWriter.class).warn("Unable to cancel disconnected SSE work");
            }
        }
    }
}
