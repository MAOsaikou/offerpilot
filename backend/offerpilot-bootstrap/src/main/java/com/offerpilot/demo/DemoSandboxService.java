package com.offerpilot.demo;

import com.offerpilot.auth.application.JwtTokenService;
import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.llm.quota.LlmQuotaIdentity;
import com.offerpilot.llm.quota.LlmUsageStore;
import com.offerpilot.privacy.AccountDataDeletionService;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** Public demo admission is serialized in PostgreSQL across application instances. */
@Service
public class DemoSandboxService {
    private final OfferPilotProperties.Demo config;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DemoResetService seeds;
    private final JwtTokenService tokens;
    private final AccountDataDeletionService deletion;
    private final LlmUsageStore usage;
    private final LlmQuotaIdentity quotas;

    public DemoSandboxService(OfferPilotProperties properties, JdbcTemplate jdbc, TransactionTemplate tx,
                              DemoResetService seeds, JwtTokenService tokens, AccountDataDeletionService deletion,
                              LlmUsageStore usage, LlmQuotaIdentity quotas) {
        this.config = properties.demo(); this.jdbc = jdbc; this.tx = tx; this.seeds = seeds;
        this.tokens = tokens; this.deletion = deletion; this.usage = usage; this.quotas = quotas;
    }
    public boolean enabled() { return config.sandboxEnabled(); }
    public record Status(Instant expiresAt, long remainingSeconds, long dailyTokenLimit, long remainingTokens, Instant budgetResetsAt) {}
    private record Sandbox(String id, String email, Instant expiresAt) {}

    public PasswordLoginService.LoginResponse login() {
        if (!config.enabled()) throw new BusinessException(ErrorCode.DEMO_NOT_ALLOWED, "体验账号未启用");
        Sandbox sandbox = tx.execute(status -> {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(782936451)");
            var day = jdbc.queryForObject("SELECT (now() AT TIME ZONE 'UTC')::date", java.time.LocalDate.class);
            int active = jdbc.queryForObject("SELECT count(*) FROM users WHERE demo_expires_at IS NOT NULL", Integer.class);
            Integer created = jdbc.query("SELECT created_count FROM demo_sandbox_admissions WHERE day=?",
                    rs -> rs.next() ? rs.getInt(1) : 0, day);
            if (active >= config.sandboxMaxActive() || created >= config.sandboxMaxPerDay())
                throw new BusinessException(ErrorCode.RATE_LIMITED, "体验沙箱名额已满，请稍后再试");
            String id = IdGenerator.next("usr");
            String email = id + "@sandbox.offerpilot.invalid";
            Instant expiry = jdbc.queryForObject("SELECT now() + (? * interval '1 second')",
                    (rs, n) -> rs.getTimestamp(1).toInstant(), config.sandboxTtl().toSeconds());
            jdbc.update("""
                    INSERT INTO users(id,email,password_hash,nickname,roles,is_demo,demo_expires_at)
                    VALUES (?,?,'!','体验沙箱','{USER}',true,?)
                    """, id, email, java.sql.Timestamp.from(expiry));
            jdbc.update("INSERT INTO user_preferences(user_id,daily_token_limit) VALUES (?,?)", id, config.sandboxDailyTokenLimit());
            jdbc.update("""
                    INSERT INTO demo_sandbox_admissions(day,created_count) VALUES (?,1)
                    ON CONFLICT(day) DO UPDATE SET created_count=demo_sandbox_admissions.created_count+1
                    """, day);
            return new Sandbox(id, email, expiry);
        });
        long start = System.nanoTime();
        try { seeds.seedIfEmpty(sandbox.id()); }
        catch (RuntimeException ex) {
            jdbc.update("UPDATE users SET demo_expires_at=now() WHERE id=?", sandbox.id());
            deletion.deleteExpiredSandbox(sandbox.id());
            throw ex;
        }
        LoggerFactory.getLogger(getClass()).info("Demo sandbox seeded durationMs={}", (System.nanoTime() - start) / 1_000_000);
        var roles = List.of("USER");
        return new PasswordLoginService.LoginResponse(tokens.issue(sandbox.id(), roles, "体验沙箱", sandbox.expiresAt()),
                Math.min(com.offerpilot.auth.application.JwtTokenService.SANDBOX_TTL_CAP.toSeconds(), Math.max(0, sandbox.expiresAt().getEpochSecond()-Instant.now().getEpochSecond())), new PasswordLoginService.UserView(sandbox.id(), sandbox.email(), "体验沙箱", roles, true, sandbox.expiresAt()));
    }

    /** Cheap status read; owner is always authenticated by the controller. */
    public Status status(String owner) {
        var expiries = jdbc.query("SELECT demo_expires_at FROM users WHERE id=? AND demo_expires_at>now()",
                (rs, n) -> rs.getTimestamp(1).toInstant(), owner);
        if (expiries.isEmpty()) throw new BusinessException(ErrorCode.DEMO_NOT_ALLOWED, "当前账号不是有效体验沙箱");
        long limit = quotas.policy(owner).dailyTokenLimit();
        var now = Instant.now();
        var day = now.atZone(ZoneOffset.UTC).toLocalDate();
        return new Status(expiries.get(0), Math.max(0, expiries.get(0).getEpochSecond()-now.getEpochSecond()), limit,
                Math.max(0, limit-usage.tokens(owner, day.toString())), day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    @Scheduled(fixedDelayString = "${offerpilot.demo.sandbox-cleanup-delay-ms:60000}")
    public void cleanupExpired() {
        // Runs even when demo admission is disabled; includes staff-suspended sandboxes.
        for (String owner : jdbc.queryForList("SELECT id FROM users WHERE demo_expires_at<=now() ORDER BY demo_expires_at LIMIT 100", String.class)) {
            try { deletion.deleteExpiredSandbox(owner); }
            catch (RuntimeException ex) { LoggerFactory.getLogger(getClass()).warn("Demo sandbox cleanup deferred"); }
        }
        jdbc.update("DELETE FROM demo_sandbox_admissions WHERE day < (now() AT TIME ZONE 'UTC')::date - 2");
    }
}
