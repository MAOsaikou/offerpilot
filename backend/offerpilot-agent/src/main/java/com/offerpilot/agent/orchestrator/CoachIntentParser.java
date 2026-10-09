package com.offerpilot.agent.orchestrator;

import com.offerpilot.agent.domain.PlanStep;
import com.offerpilot.agent.application.CoachWriteTools;
import java.util.*;

/** Resource selections come from the caller, never from model-generated task titles. */
public final class CoachIntentParser {
    private CoachIntentParser() {}
    public static boolean matches(String content) {
        // Explicit multi-domain sequences still go through the existing composite planner.
        if (content.matches("(?s).*(模拟面试|面试练习|探索职业|职业方向|简历|岗位).*(再|然后|同时|并).*")) return false;
        return List.of("学习计划", "我该补什么", "技能差距", "成长计划", "补齐技能", "学习路线", "同步成长任务", "同步到成长任务")
                .stream().anyMatch(content::contains);
    }
    public static PlanStep prepare(PlanStep step, String content, Map<String,Object> context) {
        if ("adoptGrowthPlan".equals(context.get("coachAction"))) {
            var args = new LinkedHashMap<String,Object>();
            args.put("action", "adoptGrowthPlan");
            if (context.containsKey("taskTitles")) args.put("taskTitles", context.get("taskTitles"));
            if (!args.containsKey("taskTitles")) return clarify(step);
            return new PlanStep(step.id(), "确认同步已完成成长任务", step.agent(), args, step.dependsOn());
        }
        if (CoachWriteTools.recognizes(step) || content.contains("同步成长任务") || content.contains("同步到成长任务")) return clarify(step);
        return new PlanStep(step.id(), "技能差距与学习路线", step.agent(), Map.of("action", "planLearning"), step.dependsOn());
    }
    private static PlanStep clarify(PlanStep step) {
        return new PlanStep(step.id(), "选择成长任务", step.agent(), Map.of("action", "clarify", "question",
                "请在学习路线图勾选已完成的任务并点击「同步到成长任务」，批准后记录完成状态。"), step.dependsOn());
    }
}
