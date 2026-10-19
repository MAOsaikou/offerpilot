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
    private com.offerpilot.career.application.CareerRerankForChat reranks;
    @org.springframework.beans.factory.annotation.Autowired
    public void setReranks(com.offerpilot.career.application.CareerRerankForChat reranks) { this.reranks = reranks; }
    private static com.offerpilot.career.application.CareerRecommendationService.Constraints constraints(Map<String, Object> args) {
        if (!Set.of("revisionId", "city", "minSalaryK", "avoidOvertime", "research").containsAll(args.keySet())
                || !(args.get("revisionId") instanceof String id) || id.isBlank() || id.length() > 80
                || !(args.get("city") instanceof String) || !(args.get("avoidOvertime") instanceof Boolean)
                || !(args.get("research") instanceof Boolean)
                || args.containsKey("minSalaryK") && !(args.get("minSalaryK") instanceof Integer)) throw invalid();
        return new com.offerpilot.career.application.CareerRecommendationService.Constraints((String) args.get("city"),
                (Integer) args.get("minSalaryK"), (Boolean) args.get("avoidOvertime"), (Boolean) args.get("research"));
    }
    public static boolean action(String action) {
        return Set.of("setCareerTarget", "adoptTargetCareer", "rerankCareers").contains(action);
    }
    public static boolean recognizes(PlanStep step) {
        return step.agent() == ExpertType.CAREER_DISCOVERY && action(String.valueOf(step.inputs().get("action")));
    }
    static void validate(String action, Map<String, Object> args) {
        if ("rerankCareers".equals(action)) constraints(args);
        else validate(args);
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
        if ("rerankCareers".equals(step.inputs().get("action"))) {
            var args = new java.util.LinkedHashMap<>(step.inputs());
            args.remove("action");
            constraints(args);
            reranks.revision(owner, (String) args.get("revisionId"));
            return approvals.request(owner, runId, "rerankCareers", args);
        }
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
            if ("rerankCareers".equals(step.inputs().get("action"))) {
                var saved = reranks.rerank(owner, (String) decision.args().get("revisionId"), constraints(decision.args()));
                var items = saved.items().stream().map(item -> Map.of(
                        "occupationId", item.occupation().occupationId(), "name", item.occupation().name(),
                        "family", item.occupation().family(), "hollandCode", "", "similarity", item.occupation().match(),
                        "reasons", java.util.List.of(item.changeReason()), "rankDelta", item.rankDelta(),
                        "changeReason", item.changeReason())).toList();
                return ExpertResult.card("已按批准的约束保存新推荐修订；未修改职业档案。",
                        saved.id(), "career_recommendation", Map.of("hollandCode", "", "interestsUsed", java.util.List.of("已保存推荐"),
                                "recommendations", items, "revisionId", saved.id(), "elapsedMs", saved.elapsedMs()));
            }
            validate(decision.args());
            var saved = careers.adopt(owner, (String) decision.args().get("occupationId"), (String) decision.args().get("name"));
            return ExpertResult.text("已按用户批准的参数将职业档案目标设为：" + saved.target());
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
