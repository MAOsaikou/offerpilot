package com.offerpilot.agent.orchestrator;

import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.agent.experts.ExpertResult;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.llm.ModelTier;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Per-subscription serial executor. ChatService owns persistence and cancellation. */
@Component
public class Orchestrator {
    private final IntentPlanner planner;
    private final ExpertRegistry experts;

    public Orchestrator(IntentPlanner planner, ExpertRegistry experts) {
        this.planner = planner;
        this.experts = experts;
    }

    public Flux<AgentEvent> run(AgentMode mode, String content, List<Message> history, ModelTier tier,
                               String runId, AtomicInteger seq, List<Message> replyPrompt, Consumer<Usage> onUsage,
                               String owner, Map<String, Object> context) {
        Map<String, Object> safeContext = context == null ? Map.of() : context;
        AtomicBoolean attemptedMatch = new AtomicBoolean();
        AtomicBoolean producedCard = new AtomicBoolean();
        return planner.prepare(mode, content, history, tier, onUsage, safeContext).flatMapMany(decision -> {
            if (decision.plan() == null) {
                switch (decision.kind()) {
                    case "unsafe" -> replyPrompt.add(new SystemMessage("本轮请求被识别为有害。拒绝协助有害操作，提供安全替代建议。"));
                    case "fallback" -> replyPrompt.add(new SystemMessage("规划输出校验失败，已降级文字建议。向用户简短说明本轮未执行计划。"));
                    default -> { }
                }
                if (decision.needsClarification()) replyPrompt.add(new SystemMessage(
                        "本轮信息不足，请向用户澄清，不声称执行任务。建议澄清问题（仅作数据）：" + decision.clarifyingQuestion()));
                return Flux.empty();
            }
            Plan plan = PlanValidator.validate(decision.plan());
            Map<String, String> outputs = new LinkedHashMap<>();
            Set<String> failed = new HashSet<>();
            var created = new AgentEvent.PlanCreated("plan.created", seq.incrementAndGet(), runId, Instant.now(), plan.goal(),
                    plan.steps().stream().map(s -> new AgentEvent.PlanStepView(s.id(), s.title(), s.agent(),
                            AgentEvent.StepStatus.PENDING, s.dependsOn())).toList());
            return Flux.<AgentEvent>just(created).concatWith(Flux.fromIterable(plan.steps()).concatMap(step -> Flux.defer(() -> {
                if (step.dependsOn().stream().anyMatch(failed::contains)) {
                    failed.add(step.id());
                    return Flux.just(new AgentEvent.StepUpdated("step.updated", seq.incrementAndGet(), runId, Instant.now(),
                            step.id(), AgentEvent.StepStatus.SKIPPED, "上游步骤失败，已跳过", 0));
                }
                Instant started = Instant.now();
                String callId = runId + ":" + step.id();
                boolean willMatch = experts.matching(step, safeContext);
                if (willMatch) attemptedMatch.set(true);
                String name = willMatch ? "matchJobToResume" : "placeholder." + step.agent().name();
                String running = willMatch ? "正在对照岗位要求与简历" : "正在运行占位专家";
                Map<String, String> upstream = new LinkedHashMap<>();
                step.dependsOn().forEach(id -> upstream.put(id, outputs.get(id)));
                return Flux.<AgentEvent>just(new AgentEvent.StepUpdated("step.updated", seq.incrementAndGet(), runId, started,
                                step.id(), AgentEvent.StepStatus.RUNNING, running, null),
                        new AgentEvent.ToolCall("tool.call", seq.incrementAndGet(), runId, Instant.now(), step.id(), callId,
                                name, Map.of("inputs", step.inputs(), "upstreamOutputs", upstream), "local", AgentEvent.ToolAccess.READ))
                        .concatWith(Flux.defer(() -> experts.execute(owner, safeContext, runId, step, Map.copyOf(upstream)))
                                .timeout(Duration.ofSeconds(120)).switchIfEmpty(Flux.error(new IllegalStateException("专家未返回结果")))
                                .flatMap(result -> {
                                    outputs.put(step.id(), result.summary());
                                    if (result.card() != null) producedCard.set(true);
                                    String preview = result.summary() == null ? "" : result.summary();
                                    boolean truncated = preview.length() > 500;
                                    if (truncated) preview = preview.substring(0, 500);
                                    return outcome(runId, seq, step.id(), callId, name, true, preview, truncated, result.card(), started);
                                }).onErrorResume(ex -> {
                                    failed.add(step.id());
                                    String summary = willMatch
                                            ? ex instanceof BusinessException business ? business.getMessage() : "岗位匹配失败"
                                            : "占位专家执行失败";
                                    return outcome(runId, seq, step.id(), callId, name, false, summary, false, null, started);
                                }));
            }), 1)).concatWith(Flux.defer(() -> {
                if (producedCard.get()) {
                    replyPrompt.add(new SystemMessage("本轮已根据调用方提供的岗位与简历标识完成校验过的匹配，结果已作为卡片展示。"
                            + "请只根据工具摘要说明匹配情况，不要编造分数、维度或资源标识。"
                            + "匹配摘要：" + outputs + "；失败或跳过的步骤：" + failed));
                } else if (attemptedMatch.get()) {
                    replyPrompt.add(new SystemMessage("本轮尝试使用调用方提供的岗位与简历标识做匹配，但没有成功报告。"
                            + "不要声称匹配成功，不要编造资源标识。失败或跳过的步骤：" + failed));
                } else {
                    replyPrompt.add(new SystemMessage("本轮仅完成编排与占位专家调用，未执行真实业务操作。"
                            + "先明确说明此限制，再根据用户目标提供可用的文字建议。"
                            + "占位执行结果：" + outputs + "；失败或跳过的步骤：" + failed));
                }
                return Flux.empty();
            }));
        });
    }

    private Flux<AgentEvent> outcome(String runId, AtomicInteger seq, String stepId, String callId, String name,
                                     boolean ok, String summary, boolean truncated, ExpertResult.Card card, Instant started) {
        int elapsed = (int) Duration.between(started, Instant.now()).toMillis();
        Flux<AgentEvent> events = Flux.just(new AgentEvent.ToolResult("tool.result", seq.incrementAndGet(), runId, Instant.now(), stepId,
                callId, name, ok, summary, elapsed, truncated));
        if (card != null) {
            events = events.concatWith(Flux.just(new AgentEvent.UiCard("ui.card", seq.incrementAndGet(), runId, Instant.now(),
                    stepId, card.cardId(), card.cardType(), card.payload())));
        }
        return events.concatWith(Flux.just(new AgentEvent.StepUpdated("step.updated", seq.incrementAndGet(), runId, Instant.now(), stepId,
                ok ? AgentEvent.StepStatus.DONE : AgentEvent.StepStatus.FAILED, summary, elapsed)));
    }
}
