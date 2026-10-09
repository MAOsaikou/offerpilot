package com.offerpilot.llm.quota;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class LlmQuotaLimiterTest {
    static final OfferPilotProperties.RateLimit LIMITS = new OfferPilotProperties.RateLimit(4, 2, 2, 100, 20, 15, 20, 20, 20, 20);
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-09T23:59:30Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    final MutableClock clock = new MutableClock();
    final MemoryLlmUsageStore store = new MemoryLlmUsageStore(clock);
    final LlmQuotaLimiter limiter = new LlmQuotaLimiter(owner ->
            new LlmQuotaIdentity.Policy(owner.startsWith("guest"), owner.startsWith("guest") ? 20 : 100), store, LIMITS, clock);

    @Test void guestHasLowerRpmAndRequestsAreConsumedAtEntry() {
        for (int i = 0; i < 2; i++) limiter.check("guest", ModelTier.FLASH, true);
        var failure = catchThrowableOfType(() -> limiter.check("guest", ModelTier.FLASH, true), LlmQuotaException.class);
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.LLM_RATE_LIMITED);
        assertThat(failure.retryAfterSeconds()).isEqualTo(30);
        for (int i = 0; i < 4; i++) limiter.check("user", ModelTier.PRO, true);
        assertThatThrownBy(() -> limiter.check("user", ModelTier.PRO, true)).isInstanceOf(LlmQuotaException.class);
    }
    @Test void dailyUsageIsIsolatedAndResetsAtUtcMidnight() {
        var admitted = limiter.check("guest", ModelTier.FLASH, true);
        limiter.record(admitted, 20);
        var failure = catchThrowableOfType(() -> limiter.check("guest", ModelTier.FLASH, true), LlmQuotaException.class);
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.DAILY_QUOTA_EXCEEDED);
        assertThat(failure.retryAfterSeconds()).isEqualTo(30);
        limiter.check("guest-other", ModelTier.FLASH, true);
        limiter.record(limiter.check("user", ModelTier.FLASH, true), 99);
        limiter.check("user", ModelTier.FLASH, false);
        clock.now = Instant.parse("2026-10-10T00:00:00Z");
        limiter.check("guest", ModelTier.FLASH, true);
        // A late completion belongs to the day of admission, never to the new day.
        limiter.record(admitted, 10);
        limiter.check("guest", ModelTier.FLASH, false);
    }
    @Test void guestProIsDeniedWithoutConsumingRequestsAndNoContextBypasses() {
        var failure = catchThrowableOfType(() -> limiter.check("guest", ModelTier.PRO, true), BusinessException.class);
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(failure).hasMessageContaining("登录");
        assertThat(limiter.check(null, ModelTier.PRO, true)).isNull();
        limiter.check("guest", ModelTier.FLASH, false);
        limiter.check("guest", ModelTier.FLASH, false);
        limiter.check("guest", ModelTier.FLASH, true);
        limiter.check("guest", ModelTier.FLASH, true);
    }
    @Test void concurrentAdmissionsCannotOverspendRpm() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            var results = new java.util.ArrayList<Future<Boolean>>();
            for (int i = 0; i < 30; i++) results.add(executor.submit(() -> {
                try { limiter.check("guest", ModelTier.FLASH, true); return true; }
                catch (LlmQuotaException ex) { return false; }
            }));
            int accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }
    @Test void zeroDailyLimitBlocksAndThreadScopesRestoreIdentity() {
        var disabled = new LlmQuotaLimiter(owner -> new LlmQuotaIdentity.Policy(false, 0), store, LIMITS, clock);
        assertThat(catchThrowableOfType(() -> disabled.check("user", ModelTier.FLASH, true), BusinessException.class).errorCode())
                .isEqualTo(ErrorCode.DAILY_QUOTA_EXCEEDED);
        assertThat(LlmUserContext.current()).isNull();
        assertThatThrownBy(() -> LlmUserContext.asUser("outer", () -> LlmUserContext.asUser("inner", () -> {
            assertThat(LlmUserContext.current()).isEqualTo("inner");
            throw new IllegalStateException();
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(LlmUserContext.current()).isNull();
    }
}
