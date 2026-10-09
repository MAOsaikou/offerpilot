package com.offerpilot.agent.orchestrator;

import com.offerpilot.agent.domain.PlanStep;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Structured card selections and a narrow explicit text command; no model-generated resource ids. */
public final class CareerIntentParser {
    private static final Pattern ADOPT = Pattern.compile("^(?:请)?(?:把|将)[「“\"]?([^「」“”\"]{1,100}?)[」”\"]?设为目标(?:职业)?[。！!]?$");
    private CareerIntentParser() {}
    public static PlanStep prepare(PlanStep step, String content, Map<String, Object> context) {
        var args = new LinkedHashMap<String, Object>();
        if ("setCareerTarget".equals(context.get("careerAction"))) {
            for (String key : java.util.List.of("occupationId", "name"))
                if (context.containsKey(key)) args.put(key, context.get(key));
            if (args.isEmpty()) return clarify(step);
            args.put("action", "setCareerTarget");
        } else {
            var match = ADOPT.matcher(content.strip());
            if (match.matches()) {
                args.put("action", "setCareerTarget");
                args.put("name", match.group(1).strip());
            } else if (com.offerpilot.agent.application.CareerWriteTools.recognizes(step)) return clarify(step);
            else args.put("action", "recommend");
        }
        return new PlanStep(step.id(), "setCareerTarget".equals(args.get("action")) ? "确认目标职业" : "职业兴趣推荐",
                step.agent(), args, step.dependsOn());
    }
    private static PlanStep clarify(PlanStep step) {
        return new PlanStep(step.id(), "选择目标职业", step.agent(),
                Map.of("action", "clarify", "question", "请在推荐卡片点击设为目标，或发送：请将「职业名称」设为目标职业。"), step.dependsOn());
    }
}
