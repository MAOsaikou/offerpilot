package com.offerpilot.llm.safety;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/** Exact-fragment memoization for one turn only; no semantic/keyword shortcuts or cross-user cache. */
public final class InputScreeningSession implements AutoCloseable {
    private final ConcurrentHashMap<String, FutureTask<List<String>>> fragments = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public List<String> detect(String text, Duration budget, BiFunction<String, Duration, List<String>> detector) {
        var rules = PromptInjectionDetector.detect(text);
        if (!rules.isEmpty() || text == null || text.isBlank() || closed) return rules;
        var candidate = new FutureTask<List<String>>(() -> detector.apply(text, budget));
        var existing = fragments.putIfAbsent(text, candidate);
        var task = existing == null ? candidate : existing;
        if (closed) { task.cancel(true); return rules; }
        if (existing == null) task.run();
        try {
            return task.get(Math.max(0, budget.toNanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return rules;
        } catch (Exception ex) {
            // Same fail-open behavior as the classifier. Never log raw data/provider exceptions.
            return rules;
        }
    }

    @Override public void close() {
        closed = true;
        fragments.values().forEach(task -> task.cancel(true));
        fragments.clear();
    }
}
