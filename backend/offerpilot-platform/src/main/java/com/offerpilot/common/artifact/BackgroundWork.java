package com.offerpilot.common.artifact;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Optional cooperative cancellation boundary for synchronous domain calls. Chat has its own lifecycle. */
public final class BackgroundWork {
    private static final ThreadLocal<BooleanSupplier> ACTIVE = new ThreadLocal<>();
    private BackgroundWork() {}
    public static <T> T run(BooleanSupplier active, Supplier<T> action) {
        var previous = ACTIVE.get();
        ACTIVE.set(active);
        try { check(); return action.get(); }
        finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }
    public static void check() {
        var active = ACTIVE.get();
        if (active != null && (Thread.currentThread().isInterrupted() || !active.getAsBoolean()))
            throw new BusinessException(ErrorCode.GENERATION_CANCELLED);
    }
}
