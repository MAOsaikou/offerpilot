package com.offerpilot.agent.orchestrator;

import com.offerpilot.agent.domain.PlanStep;
import java.util.Map;
import java.util.regex.Pattern;

/** Narrow explicit add command and a caller-selected job; execution still waits for HITL. */
final class BoardIntent {
    private static final Pattern ADD = Pattern.compile("^(?:请)?(?:把|将)(?:这个|该|所选)?岗位(?:加入|添加到|收藏到)(?:我的)?(?:求职)?看板[。！!]?$");
    private BoardIntent() {}
    static boolean matches(String content, Map<String, Object> context) {
        return "createApplication".equals(context.get("boardAction")) || ADD.matcher(content.strip()).matches();
    }
    static PlanStep prepare(PlanStep step, Map<String, Object> context) {
        if (!(context.get("jobId") instanceof String id) || id.isBlank() || id.length() > 80)
            return new PlanStep(step.id(), "选择岗位", step.agent(), Map.of("action", "clarify", "question", "请先在岗位详情选择要加入看板的岗位。"), step.dependsOn());
        return new PlanStep(step.id(), "确认加入看板", step.agent(), Map.of("action", "createApplication", "jobId", id), step.dependsOn());
    }
}
