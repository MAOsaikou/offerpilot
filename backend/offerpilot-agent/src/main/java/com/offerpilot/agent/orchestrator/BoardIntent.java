package com.offerpilot.agent.orchestrator;

import com.offerpilot.agent.domain.PlanStep;
import java.util.Map;
import java.util.regex.Pattern;

/** Narrow explicit add command and a caller-selected job; execution still waits for HITL. */
final class BoardIntent {
    private static final Pattern ADD = Pattern.compile("^(?:请)?(?:把|将)(?:这个|该|所选)?岗位(?:加入|添加到|收藏到)(?:我的)?(?:求职)?看板[。！!]?$");
    private static final java.util.List<Pattern> ADD_COMMANDS = java.util.List.of(ADD,
            Pattern.compile("^(?:请)?(?:把|将)(?:这个|该|所选|当前(?:选择|选中)的)(?:岗位|职位)(?:加入|添加到|收藏到|记录到)(?:我的)?(?:求职)?看板(?:[，,]我(?:之后|稍后)再投递)?[。！!]?$"),
            Pattern.compile("^(?:这个|该|所选|当前(?:选择|选中)的)(?:岗位|职位)我(?:打算|准备|想)申请[，,](?:请)?帮我(?:建|新建|创建)(?:一个|一条)?(?:求职跟踪|待投递)记录(?:[，,]先设为待投递)?[。！!]?$"),
            Pattern.compile("^我想跟进(?:这个|该|所选|当前(?:选择|选中)的)(?:岗位|职位)[，,](?:请)?在(?:我的)?(?:求职)?看板里(?:建|新建|创建)(?:一个|一条)?待投递记录[。！!]?$"));
    private BoardIntent() {}
    static boolean matches(String content, Map<String, Object> context) {
        return java.util.Set.of("createApplication", "updateApplicationStatus", "getApplicationDetail").contains(String.valueOf(context.get("boardAction")))
                || ADD_COMMANDS.stream().anyMatch(command -> command.matcher(content.strip()).matches());
    }
    static PlanStep prepare(PlanStep step, Map<String, Object> context) {
        String action = java.util.Set.of("createApplication", "updateApplicationStatus", "getApplicationDetail").contains(String.valueOf(context.get("boardAction")))
                ? String.valueOf(context.get("boardAction")) : "createApplication";
        String key = action.equals("createApplication") ? "jobId" : "applicationId";
        if (!(context.get(key) instanceof String id) || id.isBlank() || id.length() > 80)
            return new PlanStep(step.id(), "选择岗位", step.agent(), Map.of("action", "clarify", "question", action.equals("createApplication") ? "请先在岗位详情选择要加入看板的岗位。" : "请先在看板选择目标投递记录。"), step.dependsOn());
        var args = new java.util.LinkedHashMap<String,Object>();
        args.put("action", action); args.put(key, id);
        if (!action.equals("getApplicationDetail"))
            for (String field : java.util.List.of("status", "note", "nextAction", "nextActionAt"))
                if (context.containsKey(field)) args.put(field, context.get(field));
        return new PlanStep(step.id(), action.equals("getApplicationDetail") ? "读取求职详情" : "确认看板写入", step.agent(), args, step.dependsOn());
    }
}
