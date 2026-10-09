package com.offerpilot.llm.quota;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.llm.ModelTier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

@Component
public class LlmQuotaLimiter {
    public record Admission(String owner, String day, Instant dayReset) {}
    private final LlmQuotaIdentity identities;
    private final LlmUsageStore store;
    private final OfferPilotProperties.RateLimit limits;
    private final Clock clock;
    @Autowired public LlmQuotaLimiter(LlmQuotaIdentity identities, LlmUsageStore store, OfferPilotProperties properties) {
        this(identities, store, properties.rateLimit(), Clock.systemUTC());
    }
    public LlmQuotaLimiter(LlmQuotaIdentity identities, LlmUsageStore store, OfferPilotProperties.RateLimit limits, Clock clock) {
        this.identities = identities; this.store = store; this.limits = limits; this.clock = clock;
    }
    public Admission check(String owner, ModelTier tier, boolean consume) {
        // Direct/unit/live evaluation calls without an application identity deliberately bypass quotas.
        if (owner == null) return null;
        var policy = identities.policy(owner);
        // PRO is restricted to non-guest authenticated users. Never silently change the requested tier.
        if (policy.guest() && tier == ModelTier.PRO)
            throw new BusinessException(ErrorCode.FORBIDDEN, "访客暂不能使用 PRO 模型，请登录后再试");
        Instant now = clock.instant();
        long minute = now.getEpochSecond() / 60;
        Instant minuteReset = Instant.ofEpochSecond((minute + 1) * 60);
        var day = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant dayReset = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        int rpm = policy.guest() ? limits.guestPerUserPerMinute() : limits.perUserPerMinute();
        int result = store.check(owner, minute, day.toString(), rpm, Math.max(0, policy.dailyTokenLimit()), minuteReset, dayReset, consume);
        if (result != 0) throw new LlmQuotaException(result == 2 ? ErrorCode.DAILY_QUOTA_EXCEEDED : ErrorCode.LLM_RATE_LIMITED,
                Math.max(1, (result == 2 ? dayReset : minuteReset).getEpochSecond() - now.getEpochSecond()));
        return new Admission(owner, day.toString(), dayReset);
    }
    public void record(Admission admission, long tokens) {
        if (admission != null && tokens > 0) store.addTokens(admission.owner(), admission.day(), tokens, admission.dayReset());
    }
}
