package com.offerpilot.agent.application;

import com.offerpilot.agent.domain.ExpertType;
import com.offerpilot.agent.domain.PlanStep;
import com.offerpilot.agent.experts.ExpertResult;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.interview.application.InterviewCoachForChat;
import com.offerpilot.interview.application.InterviewService;
import com.offerpilot.interview.application.StarStoryService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class InterviewWriteTools {
    private final InterviewCoachForChat coach;
    private final ApprovalService approvals;
    private final StarStoryService stories;
    public InterviewWriteTools(InterviewCoachForChat coach, ApprovalService approvals, StarStoryService stories) {
        this.coach = coach; this.approvals = approvals; this.stories = stories;
    }
    public static boolean action(String action) { return Set.of("startInterview", "continueInterview", "answerInterview", "completeInterview").contains(action); }
    public static boolean recognizes(PlanStep step) { return step.agent() == ExpertType.INTERVIEWER && action(String.valueOf(step.inputs().get("action"))); }
    static void validate(String action, Map<String, Object> args) {
        if (action.equals("startInterview")) {
            if (!(args.keySet().equals(Set.of("role", "kind")) || args.keySet().equals(Set.of("role", "kind", "jobId")))
                    || (args.containsKey("jobId") && !validText(args.get("jobId"),80)) || !validText(args.get("role"), 100)
                    || !InterviewService.KINDS.contains(String.valueOf(args.get("kind")))) throw invalid();
        } else {
            Set<String> keys = action.equals("completeInterview") ? Set.of("sessionId", "expectedUpdatedAt")
                    : Set.of("sessionId", "answer", "nextQuestion", "expectedUpdatedAt");
            if (!args.keySet().equals(keys) || !validText(args.get("sessionId"), 80) || !validText(args.get("expectedUpdatedAt"), 80)) throw invalid();
            if (!action.equals("completeInterview") && (!(args.get("answer") instanceof String answer)
                    || answer.isBlank() || answer.codePointCount(0, answer.length()) > 12000 || !(args.get("nextQuestion") instanceof Boolean))) throw invalid();
        }
    }
    private static boolean validText(Object o, int max) { return o instanceof String s && !s.isBlank() && s.codePointCount(0, s.length()) <= max; }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_FAILED, "面试参数无效，请检查岗位、类型和会话。"); }
    public ApprovalService.Ticket request(String owner, String runId, PlanStep step) {
        String action = (String) step.inputs().get("action");
        var args = new LinkedHashMap<String, Object>();
        if (action.equals("startInterview")) {
            args.put("role", step.inputs().getOrDefault("role", "目标岗位"));
            args.put("kind", step.inputs().getOrDefault("kind", "项目与经历"));
            if (step.inputs().containsKey("jobId")) args.put("jobId",step.inputs().get("jobId"));
        } else {
            String id = coach.resolveSession(owner, (String) step.inputs().get("sessionId"), true);
            args.put("sessionId", id);
            var session = coach.session(owner, id);
            if (!action.equals("completeInterview") && session.completed())
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "面试已完成，请生成评分卡或开始新面试。");
            args.put("expectedUpdatedAt", session.updatedAt());
            if (!action.equals("completeInterview")) {
                args.put("answer", step.inputs().get("answer"));
                args.put("nextQuestion", step.inputs().getOrDefault("nextQuestion", false));
            }
        }
        validate(action, args);
        return approvals.request(owner, runId, action, args);
    }
    public Mono<ExpertResult> execute(String owner, String runId, PlanStep step, ApprovalService.Decision decision) {
        if (!"APPROVE".equals(decision.decision())) return Mono.error(new BusinessException(ErrorCode.APPROVAL_REJECTED,
                "TIMEOUT".equals(decision.decision()) ? "审批已超时" : "用户拒绝了面试写入"));
        return com.offerpilot.llm.UsageJournal.callable(() -> {
            String action = (String) step.inputs().get("action");
            var args = decision.args();
            validate(action, args);
            if (action.equals("completeInterview")) {
                var report = coach.completeSession(owner, (String) args.get("sessionId"), (String) args.get("expectedUpdatedAt"));
                return withCandidates(owner, (String) args.get("sessionId"), report,
                        "已按批准标记面试完成。" + com.offerpilot.interview.application.InterviewRubric.summary(report), runId, step.id());
            }
            var turn = action.equals("startInterview") ? (args.containsKey("jobId") ? coach.startSession(owner, (String) args.get("role"), (String) args.get("kind"), (String) args.get("jobId")) : coach.startSession(owner, (String) args.get("role"), (String) args.get("kind")))
                    : coach.continueSession(owner, (String) args.get("sessionId"), (String) args.get("answer"), (Boolean) args.get("nextQuestion"), (String) args.get("expectedUpdatedAt"));
            if (turn.state().equals("CLOSING")) {
                var report = coach.scoreSession(owner, turn.sessionId());
                return withCandidates(owner, turn.sessionId(), report,
                        turn.note() + com.offerpilot.interview.application.InterviewRubric.summary(report), runId, step.id());
            }
            return ExpertResult.card("面试第 " + (turn.questionIndex() + 1) + " 题：" + turn.question() + " " + turn.note(),
                    com.offerpilot.agent.experts.ExpertRegistry.requestId(runId, step.id()), "interviewer_state", turn);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private ExpertResult withCandidates(String owner, String sessionId, Object report, String summary, String runId, String stepId) {
        var candidates = stories.candidates(owner, sessionId).stream().filter(c -> !c.alreadySaved()).toList();
        if (candidates.isEmpty()) {
            return ExpertResult.card(summary, com.offerpilot.agent.experts.ExpertRegistry.requestId(runId, stepId), "interview_report", report);
        }
        return ExpertResult.cards(summary + " 可审批入库的 STAR 候选：" + candidates.size() + " 条。",
                new ExpertResult.Card(com.offerpilot.agent.experts.ExpertRegistry.requestId(runId, stepId), "interview_report", report),
                new ExpertResult.Card(com.offerpilot.agent.experts.ExpertRegistry.requestId(runId, stepId + ":stars"), "star_story_candidates", java.util.Map.of("items", candidates, "count", candidates.size())));
    }
}
