package com.offerpilot.agent.orchestrator;

import com.offerpilot.agent.domain.Plan;
import com.offerpilot.agent.domain.PlanStep;
import java.util.*;

/** Validates untrusted model output and returns a stable topological execution order. */
public final class PlanValidator {
    private PlanValidator() {}

    public static Plan validate(Plan plan) {
        if (plan == null || plan.goal() == null || plan.goal().isBlank() || plan.goal().length() > 4000
                || plan.steps() == null || plan.steps().isEmpty() || plan.steps().size() > 8)
            throw new IllegalArgumentException("计划必须包含目标与 1–8 个步骤");
        Map<String, PlanStep> remaining = new LinkedHashMap<>();
        for (var step : plan.steps()) {
            if (step == null || step.id() == null || !step.id().matches("[a-zA-Z0-9_-]{1,40}")
                    || step.title() == null || step.title().isBlank() || step.title().length() > 200
                    || step.agent() == null || step.inputs() == null || step.dependsOn() == null
                    || remaining.putIfAbsent(step.id(), step) != null)
                throw new IllegalArgumentException("步骤字段缺失或 ID 重复");
        }
        for (var step : plan.steps()) {
            if (step.dependsOn().contains(step.id()) || !remaining.keySet().containsAll(step.dependsOn())
                    || new HashSet<>(step.dependsOn()).size() != step.dependsOn().size())
                throw new IllegalArgumentException("依赖不存在、重复或引用自身");
            for (var input : step.inputs().entrySet()) {
                if (input.getKey().endsWith("From") && (!(input.getValue() instanceof String ref)
                        || !step.dependsOn().contains(ref)))
                    throw new IllegalArgumentException("From 输入必须引用直接依赖");
            }
        }
        List<PlanStep> sorted = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (!remaining.isEmpty()) {
            var next = remaining.values().stream().filter(s -> done.containsAll(s.dependsOn())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("计划依赖存在环"));
            sorted.add(next);
            done.add(next.id());
            remaining.remove(next.id());
        }
        return new Plan(plan.goal(), List.copyOf(sorted));
    }
}
