package com.offerpilot.agent.orchestrator;

import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.llm.ModelTier;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.time.*;
import java.util.*;
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
                               String runId, AtomicInteger seq, List<Message> replyPrompt, Consumer<Usage> onUsage) {
        return planner.prepare(mode, content, history, tier, onUsage).flatMapMany(decision -> {
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
                String name = "placeholder." + step.agent().name();
                Map<String, String> upstream = new LinkedHashMap<>();
                step.dependsOn().forEach(id -> upstream.put(id, outputs.get(id)));
                return Flux.<AgentEvent>just(new AgentEvent.StepUpdated("step.updated", seq.incrementAndGet(), runId, started,
                                step.id(), AgentEvent.StepStatus.RUNNING, "正在运行占位专家", null),
                        new AgentEvent.ToolCall("tool.call", seq.incrementAndGet(), runId, Instant.now(), step.id(), callId,
                                name, Map.of("inputs", step.inputs(), "upstreamOutputs", upstream), "local", AgentEvent.ToolAccess.READ))
                        .concatWith(Flux.defer(() -> experts.execute(step, Map.copyOf(upstream)))
                                .timeout(Duration.ofSeconds(120)).switchIfEmpty(Flux.error(new IllegalStateException("专家未返回结果")))
                                .flatMap(summary -> {
                                    outputs.put(step.id(), summary);
                                    return outcome(runId, seq, step.id(), callId, name, true, summary, started);
                                }).onErrorResume(ex -> {
                                    failed.add(step.id());
                                    return outcome(runId, seq, step.id(), callId, name, false, "占位专家执行失败", started);
                                }));
            }), 1)).concatWith(Flux.defer(() -> {
                replyPrompt.add(new SystemMessage("本轮仅完成编排与占位专家调用，未执行真实业务操作。"
                        + "先明确说明此限制，再根据用户目标提供可用的文字建议。"
                        + "占位执行结果：" + outputs + "；失败或跳过的步骤：" + failed));
                return Flux.empty();
            }));
        });
    }

    private Flux<AgentEvent> outcome(String runId, AtomicInteger seq, String stepId, String callId, String name,
                                     boolean ok, String summary, Instant started) {
        int elapsed = (int) Duration.between(started, Instant.now()).toMillis();
        return Flux.just(new AgentEvent.ToolResult("tool.result", seq.incrementAndGet(), runId, Instant.now(), stepId,
                        callId, name, ok, summary, elapsed, false),
                new AgentEvent.StepUpdated("step.updated", seq.incrementAndGet(), runId, Instant.now(), stepId,
                        ok ? AgentEvent.StepStatus.DONE : AgentEvent.StepStatus.FAILED, summary, elapsed));
    }
}
