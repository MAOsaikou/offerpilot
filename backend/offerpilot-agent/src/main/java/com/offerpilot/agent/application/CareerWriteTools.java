package com.offerpilot.agent.application;

import com.offerpilot.agent.domain.ExpertType;
import com.offerpilot.agent.domain.PlanStep;
import com.offerpilot.agent.experts.ExpertResult;
import com.offerpilot.career.application.CareerRecommendForChat;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Set;

@Component
public class CareerWriteTools {
    private final CareerRecommendForChat careers;
    private final ApprovalService approvals;
    public CareerWriteTools(CareerRecommendForChat careers, ApprovalService approvals) {
        this.careers = careers;
        this.approvals = approvals;
    }
    public static boolean action(String action) {
        return Set.of("setCareerTarget", "adoptTargetCareer").contains(action);
    }
    public static boolean recognizes(PlanStep step) {
        return step.agent() == ExpertType.CAREER_DISCOVERY && action(String.valueOf(step.inputs().get("action")));
    }
    static void validate(Map<String, Object> args) {
        if (!Set.of("occupationId", "name").containsAll(args.keySet()) || args.isEmpty()) throw invalid();
        for (var entry : args.entrySet()) {
            int limit = entry.getKey().equals("occupationId") ? 80 : 100;
            if (!(entry.getValue() instanceof String text) || text.isBlank()
                    || text.codePointCount(0, text.length()) > limit) throw invalid();
        }
    }
    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择有效的目标职业标识或名称");
    }
    public ApprovalService.Ticket request(String owner, String runId, PlanStep step) {
        var args = new java.util.LinkedHashMap<String, Object>();
        for (String key : Set.of("occupationId", "name"))
            if (step.inputs().containsKey(key)) args.put(key, step.inputs().get(key));
        validate(args);
        var occupation = careers.occupation((String) args.get("occupationId"), (String) args.get("name"));
        // Show the canonical name before confirmation and pin both id/name for the eventual write.
        return approvals.request(owner, runId, (String) step.inputs().get("action"),
                Map.of("occupationId", occupation.id(), "name", occupation.name()));
    }
    public Mono<ExpertResult> execute(String owner, PlanStep step, ApprovalService.Decision decision) {
        if (!"APPROVE".equals(decision.decision())) return Mono.error(new BusinessException(ErrorCode.APPROVAL_REJECTED,
                "TIMEOUT".equals(decision.decision()) ? "审批已超时" : "用户拒绝了该操作"));
        return Mono.fromCallable(() -> {
            validate(decision.args());
            var saved = careers.adopt(owner, (String) decision.args().get("occupationId"), (String) decision.args().get("name"));
            return ExpertResult.text("已按用户批准的参数将职业档案目标设为：" + saved.target());
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
