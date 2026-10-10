package com.offerpilot.agent.application;

import com.offerpilot.agent.domain.ExpertType;
import com.offerpilot.agent.domain.PlanStep;
import com.offerpilot.agent.experts.ExpertResult;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.interview.application.StarStoryService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class StarStoryWriteTools {
    public static final String ACTION = "saveStarStory";
    private final StarStoryService stories;
    private final ApprovalService approvals;

    public StarStoryWriteTools(StarStoryService stories, ApprovalService approvals) {
        this.stories = stories;
        this.approvals = approvals;
    }

    public static boolean action(String action) { return ACTION.equals(action); }
    public static boolean recognizes(PlanStep step) {
        return step.agent() == ExpertType.INTERVIEWER && action(String.valueOf(step.inputs().get("action")));
    }

    public static void validate(Map<String, Object> args) {
        if (args == null || !args.keySet().equals(Set.of("sessionId", "questionIndex"))) throw invalid();
        if (!(args.get("sessionId") instanceof String session) || session.isBlank()
                || session.codePointCount(0, session.length()) > 80) throw invalid();
        if (!(args.get("questionIndex") instanceof Number n)) throw invalid();
        int index = n.intValue();
        if (index < 0 || index > 4 || (args.get("questionIndex") instanceof Double d && d != Math.rint(d))) throw invalid();
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "STAR 故事审批参数无效");
    }

    public ApprovalService.Ticket request(String owner, String runId, PlanStep step) {
        var args = new LinkedHashMap<String, Object>();
        args.put("sessionId", step.inputs().get("sessionId"));
        Object index = step.inputs().get("questionIndex");
        args.put("questionIndex", index instanceof Number n ? n.intValue() : index);
        validate(args);
        // Re-verify candidate exists before asking the user.
        stories.candidates(owner, (String) args.get("sessionId")).stream()
                .filter(c -> c.questionIndex() == (Integer) args.get("questionIndex") && !c.alreadySaved())
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "没有可保存的 STAR 候选，或已入库"));
        return approvals.request(owner, runId, ACTION, args);
    }

    public Mono<ExpertResult> execute(String owner, String runId, PlanStep step, ApprovalService.Decision decision) {
        if (!"APPROVE".equals(decision.decision())) return Mono.error(new BusinessException(ErrorCode.APPROVAL_REJECTED,
                "TIMEOUT".equals(decision.decision()) ? "审批已超时" : "用户拒绝了 STAR 故事入库"));
        return Mono.fromCallable(() -> {
            validate(decision.args());
            var story = stories.saveFromSession(owner, (String) decision.args().get("sessionId"),
                    ((Number) decision.args().get("questionIndex")).intValue());
            return ExpertResult.card("已保存 STAR 故事：" + story.title(),
                    com.offerpilot.agent.experts.ExpertRegistry.requestId(runId, step.id()),
                    "star_story", story);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
