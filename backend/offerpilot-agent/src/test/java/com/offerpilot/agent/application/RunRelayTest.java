package com.offerpilot.agent.application;

import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class RunRelayTest {
    @org.junit.jupiter.api.Test void durableHistoryRunsOffCallerThreadAndPreservesEventOrder() {
        var history = org.mockito.Mockito.mock(WorkbenchEvents.class);
        Thread caller = Thread.currentThread();
        org.mockito.Mockito.doAnswer(call -> {
            org.assertj.core.api.Assertions.assertThat(Thread.currentThread()).isNotSameAs(caller);
            return null;
        }).when(history).append(org.mockito.ArgumentMatchers.any());
        var relay = new RunRelay(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), history);
        var now = java.time.Instant.now();
        var start = new com.offerpilot.agent.event.AgentEvent.RunStarted("",1,"durable",now,"c","m","fake",com.offerpilot.agent.domain.AgentMode.AUTO);
        var end = new com.offerpilot.agent.event.AgentEvent.RunCompleted("",2,"durable",now,"m","COMPLETED",0);
        org.assertj.core.api.Assertions.assertThat(relay.start("owner",reactor.core.publisher.Flux.just(start,end)).collectList().block(java.time.Duration.ofSeconds(3)))
                .containsExactly(start,end);
        var order = org.mockito.Mockito.inOrder(history);
        order.verify(history).append(start);
        order.verify(history).append(end);
    }
    static AgentEvent started() {
        return new AgentEvent.RunStarted("run.started", 1, "run_1", Instant.now(), "conv", "msg", "m", AgentMode.AUTO);
    }
    static AgentEvent delta(int id, String text) {
        return new AgentEvent.TextDelta("text.delta", id, "run_1", Instant.now(), null, text);
    }

    @Test void reconnectWithinGraceReplaysOnlyMissedEventsAndKeepsTheRunAlive() {
        var relay = new RunRelay(Duration.ofSeconds(5), Duration.ofMinutes(1));
        Sinks.Many<AgentEvent> model = Sinks.many().unicast().onBackpressureBuffer();
        var cancelled = new AtomicBoolean();
        var first = new CopyOnWriteArrayList<Integer>();
        var connection = relay.start("owner", model.asFlux().startWith(started()).doOnCancel(() -> cancelled.set(true)))
                .subscribe(event -> first.add(event.id()));
        model.tryEmitNext(delta(2, "a"));
        connection.dispose(); // refresh / network drop
        model.tryEmitNext(delta(3, "b"));

        assertThatThrownBy(() -> relay.resume("stranger", "run_1", 0)).isInstanceOf(BusinessException.class);
        var resumed = new CopyOnWriteArrayList<Integer>();
        relay.resume("owner", "run_1", 2).subscribe(event -> resumed.add(event.id()));
        model.tryEmitNext(delta(4, "c"));
        model.tryEmitComplete();

        assertThat(first).containsExactly(1, 2);
        assertThat(resumed).containsExactly(3, 4);
        assertThat(cancelled).isFalse();
    }

    @Test void anotherInstanceTailsSharedEventsAfterCursorAndOriginalSurvivesGrace() {
        var history=org.mockito.Mockito.mock(WorkbenchEvents.class);
        var stored=new CopyOnWriteArrayList<AgentEvent>();
        var remote=new AtomicBoolean();
        var active=new AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(c -> { stored.add(c.getArgument(0)); return null; }).when(history).append(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(history.connected("run_1")).thenAnswer(c -> remote.get());
        org.mockito.Mockito.when(history.tail(org.mockito.ArgumentMatchers.eq("owner"),org.mockito.ArgumentMatchers.eq("run_1"),org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(c -> { remote.set(true); int after=c.getArgument(2); return new WorkbenchEvents.Batch(stored.stream().filter(e -> e.id()>after).toList(),active.get()); });
        var producer=new RunRelay(Duration.ofMillis(200),Duration.ofMillis(50),history);
        var other=new RunRelay(Duration.ofMillis(200),Duration.ofMillis(50),history);
        var source=Sinks.many().unicast().<AgentEvent>onBackpressureBuffer();
        var cancelled=new AtomicBoolean();
        var initial=producer.start("owner",source.asFlux().startWith(started()).doOnCancel(() -> cancelled.set(true))).subscribe();
        source.tryEmitNext(delta(2,"a"));
        await().atMost(Duration.ofSeconds(3)).until(() -> stored.size()==2);
        var received=new CopyOnWriteArrayList<Integer>();
        var done=new AtomicBoolean();
        other.resume("owner","run_1",2).subscribe(e -> received.add(e.id()),e -> { throw new AssertionError(e); },() -> done.set(true));
        await().atMost(Duration.ofSeconds(3)).untilTrue(remote);
        initial.dispose();
        source.tryEmitNext(delta(3,"b"));
        await().pollDelay(Duration.ofMillis(350)).atMost(Duration.ofSeconds(3)).until(() -> received.size()==1);
        assertThat(cancelled).isFalse();
        source.tryEmitNext(delta(4,"c"));
        source.tryEmitNext(new AgentEvent.RunCompleted("run.completed",5,"run_1",Instant.now(),"msg","COMPLETED",0));
        source.tryEmitComplete(); active.set(false);
        await().atMost(Duration.ofSeconds(3)).untilTrue(done);
        assertThat(received).containsExactly(3,4,5);
        await().atMost(Duration.ofSeconds(3)).until(() -> producer.tracked()==0);
        assertThat(producer.resume("owner","run_1",3).map(AgentEvent::id).collectList().block(Duration.ofSeconds(3))).containsExactly(4,5);
    }

    @Test void nobodyReconnectingCancelsTheRunAfterGrace() {
        var relay = new RunRelay(Duration.ofMillis(50), Duration.ofMillis(50));
        Sinks.Many<AgentEvent> model = Sinks.many().unicast().onBackpressureBuffer();
        var cancelled = new AtomicBoolean();
        relay.start("owner", model.asFlux().startWith(started()).doOnCancel(() -> cancelled.set(true))).subscribe().dispose();
        await().atMost(Duration.ofSeconds(5)).untilTrue(cancelled);
        await().atMost(Duration.ofSeconds(5)).until(() -> relay.tracked() == 0);
        assertThatThrownBy(() -> relay.resume("owner", "run_1", 0)).isInstanceOf(BusinessException.class);
    }
}
