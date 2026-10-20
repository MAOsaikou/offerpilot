package com.offerpilot.job.application;

import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded admission also bounds damage from an upstream that ignores interruption. */
@Component
public class JobSearchWork {
    private final ExecutorService pool = new ThreadPoolExecutor(12, 12, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(24), runnable -> {
                Thread thread = new Thread(runnable, "job-search"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    public <T> Future<T> submit(Supplier<T> work) { return pool.submit(work::get); }
    static <T> T await(Future<T> work, long deadline, T fallback) {
        if (work == null) return fallback;
        try { return work.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); return fallback; }
        catch (ExecutionException | TimeoutException | CancellationException ex) { return fallback; }
        finally { if (!work.isDone()) work.cancel(true); }
    }
    @PreDestroy public void close() { pool.shutdownNow(); }
}
