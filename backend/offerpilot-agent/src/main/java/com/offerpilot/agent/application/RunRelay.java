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
 * after which clients tail the shared persisted event store. Process failure cannot resume the model call.
 */
@Component
public class RunRelay {
    private final WorkbenchEvents history;
    private final Duration grace;
    private final Duration retention;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    public RunRelay(Duration grace, Duration retention) { this(grace, retention, (WorkbenchEvents) null); }

    @org.springframework.beans.factory.annotation.Autowired
    public RunRelay(@Value("${offerpilot.agent.reconnect-grace:PT30S}") Duration grace,
                    @Value("${offerpilot.agent.replay-retention:PT2M}") Duration retention,
                    org.springframework.beans.factory.ObjectProvider<WorkbenchEvents> history) {
        this(grace, retention, history.getIfAvailable());
    }

    public RunRelay(Duration grace, Duration retention, WorkbenchEvents history) {
        this.grace = grace;
        this.retention = retention;
        this.history = history;
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
                            pendingCancel.update(Mono.delay(grace,reactor.core.scheduler.Schedulers.boundedElastic()).subscribe(tick -> abandon()));
                    });
        }

        /** No viewer came back: stop the model like a disconnect did before relay existed. */
        void abandon() {
            if (viewers.get() > 0 || done.get()) return;
            if (history != null && history.connected(runId)) {
                pendingCancel.update(Mono.delay(grace,reactor.core.scheduler.Schedulers.boundedElastic()).subscribe(tick -> abandon()));
                return;
            }
            if (!done.compareAndSet(false, true)) return;
            upstream.dispose();
            events.tryEmitComplete();
            persistCompletion();
            forgetLater();
        }

        void finish() {
            if (done.compareAndSet(false, true)) { persistCompletion(); forgetLater(); }
            pendingCancel.dispose();
        }

        synchronized void persistCompletion() {
            if (history!=null && runId!=null) {
                try { history.complete(runId); }
                catch (RuntimeException unavailable) {
                    org.slf4j.LoggerFactory.getLogger(RunRelay.class).warn("运行事件终态标记暂不可用 ({})", unavailable.getClass().getSimpleName());
                }
            }
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
        Flux<AgentEvent> recorded = history == null ? source : source
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic(), 1).doOnNext(event -> {
                    synchronized (run) {
                        if (run.done.get()) return;
                        if (run.runId==null) { run.runId=event.runId(); runs.put(event.runId(),run); }
                        history.append(event);
                    }
                });
        run.upstream.update(recorded
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
        if (run == null && history != null) { history.authorize(owner,runId); return durable(owner,runId,Math.max(after,0)); }
        if (run == null || !Objects.equals(run.owner, owner)) throw new BusinessException(ErrorCode.RUN_NOT_FOUND);
        return run.view(Math.max(after, 0));
    }

    private Flux<AgentEvent> durable(String owner,String runId,int after) {
        return Flux.defer(() -> {
            AtomicInteger cursor=new AtomicInteger(after);
            AtomicBoolean finished=new AtomicBoolean();
            return Flux.interval(Duration.ZERO,Duration.ofMillis(250))
                    .concatMap(tick -> Mono.fromCallable(() -> history.tail(owner,runId,cursor.get()))
                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()),1)
                    .concatMap(batch -> {
                        var events=batch.events().stream().filter(e -> e.id()>cursor.get()).toList();
                        if (!events.isEmpty()) cursor.set(events.get(events.size()-1).id());
                        if (!batch.active() && events.isEmpty()) finished.set(true);
                        return Flux.fromIterable(events);
                    }).takeUntil(event -> event instanceof AgentEvent.RunCompleted)
                    .takeUntilOther(Flux.interval(Duration.ofMillis(100)).filter(tick -> finished.get()).next());
        });
    }

    public java.util.List<AgentEvent> history(String owner, String runId) {
        if (history == null) throw new BusinessException(ErrorCode.RUN_NOT_FOUND);
        return history.read(owner, runId);
    }

    int tracked() { return runs.size(); }
}
