package com.offerpilot.llm;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/** Invocation audit; correlation is supplied by the authenticated chat application, never by the client. */
@Component
public class UsageJournal {
    private static final ThreadLocal<Scope> LOCAL = new ThreadLocal<>();
    public static Scope current() { return LOCAL.get(); }
    public static <T> T asScope(Scope scope, java.util.function.Supplier<T> action) {
        Scope previous=LOCAL.get();
        if(scope != null) LOCAL.set(scope);
        try { return action.get(); }
        finally { if(previous == null) LOCAL.remove(); else LOCAL.set(previous); }
    }
    public static <T> reactor.core.publisher.Mono<T> callable(java.util.concurrent.Callable<T> action) {
        return reactor.core.publisher.Mono.deferContextual(context -> reactor.core.publisher.Mono.fromCallable(() -> {
            Scope previous = LOCAL.get();
            Scope scope = context.getOrDefault(KEY, previous);
            if (scope != null) LOCAL.set(scope);
            try { return action.call(); }
            finally { if (previous == null) LOCAL.remove(); else LOCAL.set(previous); }
        }));
    }
    public record Summary(String model, int promptTokens, int completionTokens, Long durationMs,
                          Double costEstimate, boolean usageReported, boolean fallbackUsed) {}
    public Summary summary(String owner, String messageId) {
        return jdbc.query("SELECT string_agg(DISTINCT model, ', ' ORDER BY model) model, sum(prompt_tokens) input, sum(completion_tokens) output, sum(duration_ms) duration, CASE WHEN bool_and(cost_estimate IS NOT NULL) THEN sum(cost_estimate) END cost, bool_and(usage_reported) reported FROM llm_usage_logs WHERE user_id=? AND message_id=? HAVING count(*)>0",
                (rs, i) -> new Summary(rs.getString("model"),rs.getInt("input"),rs.getInt("output"),rs.getLong("duration"),
                        rs.getObject("cost") == null ? null : rs.getBigDecimal("cost").doubleValue(),rs.getBoolean("reported"),false), owner,messageId)
                .stream().findFirst().orElse(null);
    }
    public static final String KEY = UsageJournal.class.getName();
    public record Scope(String owner, String runId, String messageId) {}
    private final JdbcTemplate jdbc;
    private final Environment environment;
    public UsageJournal(JdbcTemplate jdbc, Environment environment) { this.jdbc = jdbc; this.environment = environment; }

    public BigDecimal cost(ModelTier tier, int input, int output, boolean reported) {
        String prefix = "offerpilot.llm.pricing." + tier.name().toLowerCase(java.util.Locale.ROOT);
        BigDecimal in, out;
        try {
            in = environment.getProperty(prefix + ".input-per-million", BigDecimal.class);
            out = environment.getProperty(prefix + ".output-per-million", BigDecimal.class);
        } catch (org.springframework.core.convert.ConversionException invalidPrice) { return null; }
        if (!reported || in == null || out == null || in.signum() < 0 || out.signum() < 0) return null;
        return in.multiply(BigDecimal.valueOf(input)).add(out.multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1_000_000), 10, RoundingMode.HALF_UP);
    }

    public Flux<ChatResponse> observe(Scope scope, ModelTier tier, String model, Flux<ChatResponse> source) {
        return Flux.defer(() -> {
            long started = System.nanoTime();
            var actualModel = new java.util.concurrent.atomic.AtomicReference<>(model);
            int[] tokens = {0, 0};
            boolean[] reported = {false};
            var saved = new AtomicBoolean();
            java.util.function.Consumer<String> finish = status -> {
                if (!saved.compareAndSet(false, true)) return;
                jdbc.update("INSERT INTO llm_usage_logs(id,user_id,run_id,message_id,model,prompt_tokens,completion_tokens,duration_ms,cost_estimate,usage_reported,status) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID().toString(), scope.owner(), scope.runId(), scope.messageId(), actualModel.get(),
                        tokens[0], tokens[1], Math.max(0, (System.nanoTime()-started)/1_000_000),
                        cost(tier,tokens[0],tokens[1],reported[0]), reported[0], status);
            };
            return source.doOnNext(response -> {
                String reportedModel=response.getMetadata().getModel();
                if(reportedModel != null && !reportedModel.isBlank()) actualModel.set(reportedModel);
                var usage = response.getMetadata().getUsage();
                if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
                    reported[0] = true;
                    tokens[0] = Math.max(tokens[0], usage.getPromptTokens() == null ? 0 : usage.getPromptTokens());
                    tokens[1] = Math.max(tokens[1], usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
                }
            }).doOnComplete(() -> finish.accept("COMPLETED"))
              .doOnError(error -> finish.accept("FAILED"))
              .doOnCancel(() -> finish.accept("CANCELLED"));
        });
    }
}
