package com.offerpilot.agent.application;

import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AG-07: decouples a chat run from the HTTP connection that started it.
 * <p>
 * The run's events go into a per-run replay buffer. A dropped connection (refresh, network switch) no longer
 * cancels the run at once: the run keeps going for a grace period, and a client can reattach with
 * {@code Last-Event-ID} and receive every event after that id, then the live tail. If nobody reattaches within
 * the grace period the run is cancelled as before (saved as CANCELLED; durable approvals move to the workbench).
 * Explicit stop still goes through {@link ChatService#cancel}. Finished runs stay replayable for a short time,
 * after which clients fall back to the persisted message.
 */
@Component
public class RunRelay {
    private final Duration grace;
    private final Duration retention;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    public RunRelay(@Value("${offerpilot.agent.reconnect-grace:PT30S}") Duration grace,
                    @Value("${offerpilot.agent.replay-retention:PT2M}") Duration retention) {
        this.grace = grace;
        this.retention = retention;
    }

    private final class Run {
        final String owner;
        final Sinks.Many<AgentEvent> events = Sinks.many().replay().all();
        final Disposable.Swap upstream = Disposables.swap();
        final Disposable.Swap pendingCancel = Disposables.swap();
        final AtomicInteger viewers = new AtomicInteger();
        final AtomicBoolean done = new AtomicBoolean();
        volatile String runId;

        Run(String owner) { this.owner = owner; }

        Flux<AgentEvent> view(int after) {
            return events.asFlux()
                    .filter(event -> event.id() > after)
                    .doOnSubscribe(ignored -> {
                        viewers.incrementAndGet();
                        pendingCancel.update(null);
                    })
                    .doFinally(ignored -> {
                        if (viewers.decrementAndGet() == 0 && !done.get())
                            pendingCancel.update(Mono.delay(grace).subscribe(tick -> abandon()));
                    });
        }

        /** No viewer came back: stop the model like a disconnect did before relay existed. */
        void abandon() {
            if (viewers.get() > 0 || !done.compareAndSet(false, true)) return;
            upstream.dispose();
            events.tryEmitComplete();
            forgetLater();
        }

        void finish() {
            if (done.compareAndSet(false, true)) forgetLater();
            pendingCancel.dispose();
        }

        void forgetLater() {
            if (runId != null) Mono.delay(retention).subscribe(tick -> runs.remove(runId, this));
        }
    }

    /** Starts the run detached from any connection and returns the first viewer's stream. */
    public Flux<AgentEvent> start(String owner, Flux<AgentEvent> source) {
        Run run = new Run(owner);
        // Attach the first viewer before the source runs so the grace timer only starts after a real disconnect.
        Flux<AgentEvent> first = run.view(0);
        run.viewers.incrementAndGet();
        run.upstream.update(source
                .doOnNext(event -> {
                    if (run.runId == null) {
                        run.runId = event.runId();
                        runs.put(event.runId(), run);
                    }
                })
                .subscribe(
                        event -> run.events.tryEmitNext(event),
                        error -> { run.events.tryEmitError(error); run.finish(); },
                        () -> { run.events.tryEmitComplete(); run.finish(); }));
        // Hand the reserved viewer slot to the real subscriber.
        return first.doOnSubscribe(ignored -> run.viewers.decrementAndGet());
    }

    /** Reattach to a run of this owner and receive events with id greater than {@code after}. */
    public Flux<AgentEvent> resume(String owner, String runId, int after) {
        Run run = runs.get(runId);
        if (run == null || !Objects.equals(run.owner, owner)) throw new BusinessException(ErrorCode.RUN_NOT_FOUND);
        return run.view(Math.max(after, 0));
    }

    int tracked() { return runs.size(); }
}
