package com.offerpilot.agent.orchestrator;

import com.offerpilot.agent.domain.PlanStep;
import com.offerpilot.interview.application.InterviewService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Only caller context or explicit user text supplies session ids; never accept model-invented ids. */
public final class InterviewIntentParser {
    private static final Pattern ID = Pattern.compile("\\biv_[A-Za-z0-9_-]+\\b");
    private static final Pattern ROLE = Pattern.compile("(?:岗位|职位|角色)\\s*[:：]\\s*([^，。；\\n]{1,100})");
    private static final Pattern ROLE_INTERVIEW = Pattern.compile("(?:模拟|文字)?面试\\s*[:：]\\s*([^，。；\\n]{1,100})");
    private static final Pattern NATURAL_ROLE = Pattern.compile("(?:模拟|进行|开始|练习|练练|准备)(?:一下|一场|一次)?\\s*([^，。；\\n]{1,100}?)(?:岗位)?面试");
    /** A request to practise, anchored at the start, so an interview answer that mentions practice stays an answer. */
    private static final Pattern PRACTICE = Pattern.compile("(?s)(?:请)?(?:帮我|给我|陪我)?(?:我想|我想要|我要|我需要)?(?:练习|练练|练一下|准备)(?:一下|一场|一次)?[^，。；\\n]{0,40}面试.*");
    private static final Pattern ASK_FIRST = Pattern.compile("(?s)(?:请)?(?:帮我|给我|陪我)?(?:我想|我想要|我要)?(?:来|做|进行)?(?:一场|一次)?[^，。；\\n]{0,40}面试[，,]?\\s*(?:先|请)?(?:问我|出(?:一|几|两|三)?道?题|提问|考考我).*");
    private static final Set<String> ACTIONS = Set.of("startInterview", "continueInterview", "answerInterview", "scoreInterview", "generateReport", "completeInterview", "saveStarStory", "recallStarStories");
    private InterviewIntentParser() {}
    public static PlanStep prepare(PlanStep step, String content, Map<String, Object> context) {
        if (context.containsKey("interviewAction") && !(context.get("interviewAction") instanceof String s && ACTIONS.contains(s)))
            throw new com.offerpilot.common.error.BusinessException(com.offerpilot.common.error.ErrorCode.VALIDATION_FAILED,
                    "面试动作无效，请选择开始、继续、评分或引用故事。");
        if (context.containsKey("interviewSessionId") && !(context.get("interviewSessionId") instanceof String s && !s.isBlank() && s.length() <= 80))
            throw new com.offerpilot.common.error.BusinessException(com.offerpilot.common.error.ErrorCode.VALIDATION_FAILED,
                    "面试会话标识无效，请重新选择练习。");
        var args = new LinkedHashMap<String, Object>();
        String action = context.get("interviewAction") instanceof String s && ACTIONS.contains(s) ? s : null;
        if (action == null && scoringRequest(content)) action = "scoreInterview";
        if (action == null && step.inputs().get("action") instanceof String proposed && ACTIONS.contains(proposed)) action = proposed;
        if (action == null) {
            if (content.strip().matches("(?s)(?:请)?(?:帮我|给我)?(?:结束面试|完成面试|标记完成).*")) action = "completeInterview";
            else if (content.strip().matches("(?s)(?:请)?(?:帮我|给我|我想|我想要)?(?:做|来|进行)?(?:一场)?(?:模拟.*面试|面试练习|文字面试|开始面试|新面试).*")) action = "startInterview";
            else if (PRACTICE.matcher(content.strip()).matches() || ASK_FIRST.matcher(content.strip()).matches()) action = "startInterview";
            else if (content.strip().matches("(?is)(?:请|帮我|给我)?(?:评价|评分|复盘|生成.*报告|做.*评分|面试(?:反馈|评分|报告)|score|STAR\\s*(?:评价|评分)).*")
                    || content.matches("(?s).*(?:给我|生成|请求).*(?:面试反馈|评分卡|面试报告).*")) action = "scoreInterview";
            else if (recallRequest(content)) action = "recallStarStories";
            else action = "continueInterview";
        }
        if (action.equals("continueInterview") && recallRequest(content))
            action = "recallStarStories";
        if (action.equals("generateReport")) action = "scoreInterview";
        if (action.equals("answerInterview")) action = "continueInterview";
        args.put("action", action);
        String id = string(context.get("interviewSessionId"));
        var ids = ID.matcher(content);
        if (id == null && ids.find()) id = ids.group();
        if (id != null) args.put("sessionId", id);
        if (action.equals("saveStarStory")) {
            if (id != null) args.put("sessionId", id);
            Object index = context.get("questionIndex");
            if (index instanceof Number n) args.put("questionIndex", n.intValue());
            else if (index instanceof String s && s.matches("[0-4]")) args.put("questionIndex", Integer.parseInt(s));
            return new PlanStep(step.id(), "保存 STAR 故事", step.agent(), args, step.dependsOn());
        }
        if (action.equals("startInterview")) {
            String role = string(context.get("role"));
            var roleMatch = ROLE.matcher(content);
            var interviewRole = ROLE_INTERVIEW.matcher(content);
            if (role == null && roleMatch.find()) role = roleMatch.group(1).strip();
            if (role == null && interviewRole.find()) role = interviewRole.group(1).strip();
            var naturalRole = NATURAL_ROLE.matcher(content);
            if (role == null && naturalRole.find() && !Set.of("文字", "一场", "一次", "一下", "项目与经历", "表达与结构", "职业动机").contains(naturalRole.group(1))) role = naturalRole.group(1).strip();
            args.put("role", role == null ? "目标岗位" : role);
            String kind = string(context.get("kind"));
            if (kind == null) kind = InterviewService.KINDS.stream().filter(content::contains).sorted().findFirst().orElse("项目与经历");
            args.put("kind", kind);
            if (string(context.get("jobId")) != null) args.put("jobId",string(context.get("jobId")));
        } else if (action.equals("continueInterview")) {
            args.put("answer", content);
            args.put("nextQuestion", content.strip().matches("(?:请)?(?:下一题|跳过追问|跳过本题)[。！!]?"));
        }
        return new PlanStep(step.id(), action.equals("scoreInterview") ? "面试 STAR 评分卡" : "文字模拟面试", step.agent(), args, step.dependsOn());
    }
    public static boolean scoringRequest(String content) {
        String text = content.strip();
        // Anchor to a request, so an answer describing product scores/feedback stays an answer.
        if (text.matches("(?s)(?:请)?(?:帮我|给我)?(?:结束面试|完成面试|标记完成).*")) return false;
        return text.matches("(?is)(?:请)?(?:帮我|给我|为我|我想要|我想)?(?:给|对|为)?(?:这场|本场|刚才的|我的|这次)?(?:面试|表现|回答)?(?:的)?(?:评分|打分|分数|得分|评价|复盘|反馈|score).*")
                || text.matches("(?s)(?:请)?(?:帮我|给我|为我|我想要|我想)?(?:评价|点评)(?:一下)?(?:我的|这场面试的|本场面试的|刚才的)?(?:表现|面试|回答).*")
                || text.matches("(?s)(?:请)?(?:帮我|给我|为我|生成|请求).*(?:面试反馈|评分卡|面试报告).*");
    }
    private static boolean recallRequest(String content) {
        return content.strip().matches("(?s)(?:请|帮我|基于|根据|参考|引用|召回).*(?:引用|召回|参考|故事库|已入库).*(?:STAR|故事|项目经历).*");
    }
    private static String string(Object value) { return value instanceof String s && !s.isBlank() ? s.strip() : null; }
}
