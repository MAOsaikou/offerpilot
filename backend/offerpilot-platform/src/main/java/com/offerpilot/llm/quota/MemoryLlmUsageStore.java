package com.offerpilot.llm.quota;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Test-only, single-process counters. Production requires shared Redis. */
public final class MemoryLlmUsageStore implements LlmUsageStore {
    private record Counter(long value, Instant expires) {}
    private final Map<String, Counter> counters = new HashMap<>();
    private final Clock clock;
    public MemoryLlmUsageStore(Clock clock) { this.clock = clock; }
    private void purge() { counters.values().removeIf(c -> !c.expires().isAfter(clock.instant())); }
    private long value(String key) { return counters.getOrDefault(key, new Counter(0, Instant.EPOCH)).value(); }
    @Override public synchronized int check(String owner, long minute, String day, int rpm, long dailyLimit,
            Instant minuteReset, Instant dayReset, boolean consume) {
        purge();
        String requestKey = owner + ":rpm:" + minute;
        if (value(owner + ":tokens:" + day) >= dailyLimit) return 2;
        if (value(requestKey) >= rpm) return 1;
        if (consume) counters.put(requestKey, new Counter(value(requestKey) + 1, minuteReset));
        return 0;
    }
    @Override public synchronized void addTokens(String owner, String day, long tokens, Instant dayReset) {
        purge();
        String key = owner + ":tokens:" + day;
        counters.put(key, new Counter(value(key) + tokens, dayReset.plusSeconds(3600)));
    }
}
