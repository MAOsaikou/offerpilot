package com.offerpilot.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SseWriterTest {
    private final ObjectMapper json = new ObjectMapper();
    private SseWriter writer(SseEmitter emitter, Duration interval, Runnable disconnect) {
        return new SseWriter(json, emitter, interval, "run_test", "服务暂时失败，请重试", disconnect);
    }

    @Test void completionWaitsForAnInFlightHeartbeatAndNeverWritesAgain() throws Exception {
        var emitter = mock(SseEmitter.class);
        var sending = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var writes = new AtomicInteger();
        doAnswer(call -> {
            writes.incrementAndGet();
            sending.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        var writer = writer(emitter, Duration.ofMillis(2), () -> {});
        try {
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            var completing = new Thread(() -> { writer.complete(); finished.countDown(); });
            completing.start();
            // complete must not flush/dispatch while the heartbeat is inside send.
            assertThat(finished.await(30, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            int finalWrites = writes.get();
            writer.complete();
            writer.fail(new IllegalStateException("late error"));
            writer.send("ui.card", Map.of());
            Thread.sleep(30); // Allow an already queued heartbeat to reach the stopped guard.
            assertThat(writes.get()).isEqualTo(finalWrites);
            verify(emitter, times(1)).complete();
            verify(emitter, never()).completeWithError(any());
        } finally {
            release.countDown();
            writer.complete();
        }
    }

    @Test void earlyTerminalCompletionStopsHeartbeatWithoutAnyContainerCallback() throws Exception {
        var emitter = mock(SseEmitter.class); // Deliberately never invoke onCompletion: Spring early-complete path.
        var cancelled = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var writer = writer(emitter, Duration.ofMillis(2), () -> {});
        doAnswer(call -> { done.countDown(); return null; }).when(emitter).complete();
        writer.subscribe(Flux.just("COMPLETED").concatWith(Flux.never()).doOnCancel(cancelled::countDown),
                status -> writer.send("run.completed", Map.of("status", status)));
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        int writes = mockingDetails(emitter).getInvocations().size();
        Thread.sleep(30);
        assertThat(mockingDetails(emitter).getInvocations()).hasSize(writes);
        verify(emitter, times(1)).complete();
    }

    @ParameterizedTest @ValueSource(strings = {"io", "state", "recycled"})
    void failedSendStopsAndCancelsWithoutFlushingTheBrokenResponse(String failure) throws Exception {
        var emitter = mock(SseEmitter.class);
        Throwable error = switch (failure) {
            case "io" -> new IOException("disconnected");
            case "state" -> new IllegalStateException("already complete");
            default -> new NullPointerException("recycled response");
        };
        doThrow(error).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        var subscribed = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var disconnects = new AtomicInteger();
        var writer = writer(emitter, Duration.ofDays(1), disconnects::incrementAndGet);
        writer.subscribe(Flux.never().doOnSubscribe(ignored -> subscribed.countDown()).doOnCancel(cancelled::countDown), ignored -> {});
        assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatCode(() -> {
            writer.send("text.delta", Map.of("text", "hello"));
            writer.complete(); writer.fail(error); writer.send("run.completed", Map.of());
        }).doesNotThrowAnyException();
        assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(disconnects.get()).isEqualTo(1);
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();
        verify(emitter, never()).completeWithError(any());
    }

    @Test void boundedSseTextDoesNotSerializeHalfASurrogatePair() throws Exception {
        String text = "中😀文";
        var emitter = mock(SseEmitter.class);
        var frame = new AtomicReference<String>();
        doAnswer(call -> { frame.set(frame(call.getArgument(0))); return null; }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        var writer = writer(emitter, Duration.ofDays(1), () -> {});
        try {
            for (int limit = 0; limit <= text.length(); limit++) {
                String bounded = com.offerpilot.common.text.TextBounds.truncate(text, limit);
                writer.send("text.delta", Map.of("text", bounded));
                String encoded = new String(frame.get().getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(encoded).isEqualTo(frame.get());
                var decoded = json.readTree(encoded.split("data:", 2)[1].strip()).path("text").asText();
                assertThat(decoded).isEqualTo(switch (limit) { case 0 -> ""; case 1, 2 -> "中"; case 3 -> "中😀"; default -> text; });
            }
        } finally { writer.complete(); }
    }

    @Test void businessAndUnknownErrorsKeepTheEnvelopeAndDoNotDispatchMvcErrors() throws Exception {
        for (Throwable error : new Throwable[]{new BusinessException(ErrorCode.MODEL_UNAVAILABLE), new RuntimeException("private provider body")}) {
            var emitter = mock(SseEmitter.class);
            var frame = new AtomicReference<String>();
            doAnswer(call -> { frame.set(frame(call.getArgument(0))); return null; }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
            var writer = writer(emitter, Duration.ofDays(1), () -> {});
            writer.send(7, "text.delta", "run_existing", Map.of("text", "hello"), false);
            writer.fail(error);
            assertThat(frame.get()).startsWith("id:8\nevent:error\ndata:").endsWith("\n\n");
            var event = json.readTree(frame.get().split("data:", 2)[1].strip());
            assertThat(event.path("runId").asText()).isEqualTo("run_existing");
            assertThat(event.path("id").asInt()).isEqualTo(8);
            assertThat(event.path("ts").asText()).isNotEmpty();
            assertThat(event.path("recoverable").asBoolean()).isFalse();
            assertThat(event.path("code").asInt()).isEqualTo(error instanceof BusinessException ? 40010 : 50000);
            assertThat(frame.get()).doesNotContain("private provider body");
            verify(emitter, times(1)).complete();
            verify(emitter, never()).completeWithError(any());
        }
    }

    @Test void timeoutOrDisconnectBeforeSubscriptionPreventsModelWork() throws Exception {
        for (boolean timeout : new boolean[]{true, false}) {
            var emitter = mock(SseEmitter.class);
            var callback = new AtomicReference<Runnable>();
            if (timeout) doAnswer(call -> { callback.set(call.getArgument(0)); return null; }).when(emitter).onTimeout(any());
            else doAnswer(call -> { Consumer<Throwable> c = call.getArgument(0); callback.set(() -> c.accept(new IOException())); return null; }).when(emitter).onError(any());
            var started = new AtomicBoolean();
            var cancellations = new AtomicInteger();
            var writer = writer(emitter, Duration.ofDays(1), cancellations::incrementAndGet);
            callback.get().run(); callback.get().run();
            writer.subscribe(Flux.defer(() -> { started.set(true); return Flux.never(); }), ignored -> {});
            writer.send("text.delta", Map.of()); writer.complete();
            assertThat(started.get()).isFalse();
            assertThat(cancellations.get()).isEqualTo(1);
            verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter, never()).complete();
        }
    }

    @Test void heartbeatFailureIsHandledWithoutDroppedErrorsOrAnotherWrite() throws Exception {
        var emitter = mock(SseEmitter.class);
        var disconnected = new CountDownLatch(1);
        doThrow(new IllegalStateException("already complete")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        var writer = writer(emitter, Duration.ofMillis(2), disconnected::countDown);
        assertThat(disconnected.await(5, TimeUnit.SECONDS)).isTrue();
        writer.send("run.completed", Map.of()); writer.complete();
        Thread.sleep(30);
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();
        verify(emitter, never()).completeWithError(any());
    }

    @Test void serializationFailureProducesASafeErrorInsteadOfMvcDispatch() throws Exception {
        var emitter = mock(SseEmitter.class);
        var frames = new java.util.ArrayList<String>();
        doAnswer(call -> { frames.add(frame(call.getArgument(0))); return null; }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        var writer = writer(emitter, Duration.ofDays(1), () -> {});
        writer.send("ui.card", Map.of("payload", new Object())); // Jackson FAIL_ON_EMPTY_BEANS.
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0)).contains("event:error", "\"code\":50000", "\"recoverable\":false")
                .doesNotContain("No serializer found");
        verify(emitter, times(1)).complete();
        verify(emitter, never()).completeWithError(any());
    }

    @Test void cancellingAnInFlightTimerDoesNotEnqueueOnADisposedWorker() throws Exception {
        // A timer tick is already in progress when the terminal event cancels its worker.
        // With interval -> publishOn, cancellation can dispose the delivery worker just
        // before its pending schedule() call, dropping a Scheduler unavailable error.
        var scheduler = mock(reactor.core.scheduler.Scheduler.class);
        var worker = mock(reactor.core.scheduler.Scheduler.Worker.class);
        var tick = new AtomicReference<Runnable>();
        var paused = new AtomicBoolean();
        var disposed = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var dropped = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        when(scheduler.createWorker()).thenReturn(worker);
        doAnswer(call -> { disposed.set(true); return null; }).when(worker).dispose();
        when(worker.schedulePeriodically(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(call -> { tick.set(call.getArgument(0)); return mock(reactor.core.Disposable.class); });
        when(worker.schedule(any(Runnable.class))).thenAnswer(call -> {
            if (paused.get()) {
                entered.countDown();
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                if (disposed.get()) throw reactor.core.Exceptions.failWithRejected();
            }
            ((Runnable)call.getArgument(0)).run();
            return mock(reactor.core.Disposable.class);
        });
        var emitter = mock(SseEmitter.class);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        reactor.core.publisher.Hooks.onErrorDropped(dropped::add);
        var writer = new SseWriter(json, emitter, Duration.ofMillis(2), "run_test", "failure", () -> {}, scheduler);
        var ticking = new Thread(() -> tick.get().run());
        var completing = new Thread(() -> { writer.complete(); finished.countDown(); });
        try {
            paused.set(true);
            ticking.start();
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            completing.start();
            // The serialized writer either blocks completion behind send, or the old
            // pipeline disposes the worker while a heartbeat is still being enqueued.
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                    completing.getState() == Thread.State.BLOCKED || disposed.get());
            release.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
            ticking.join(30_000);
            assertThat(ticking.isAlive()).isFalse();
            assertThat(dropped).isEmpty();
            tick.get().run(); // A late timer callback is harmless after completion.
            verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter, times(1)).complete();
        } finally {
            release.countDown();
            ticking.join(30_000);
            completing.join(30_000);
            writer.complete();
            reactor.core.publisher.Hooks.resetOnErrorDropped();
            scheduler.dispose();
        }
    }

    private static String frame(SseEmitter.SseEventBuilder builder) {
        return builder.build().stream().map(item -> item.getData().toString()).reduce("", String::concat);
    }
}
