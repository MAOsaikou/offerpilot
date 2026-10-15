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
