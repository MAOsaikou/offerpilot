package com.offerpilot.agent.application;

import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.experts.ExpertResult;
import com.offerpilot.career.application.*;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;

@Component
public class CoachWriteTools {
    private final CoachPlanForChat coach;
    private final ApprovalService approvals;
    public CoachWriteTools(CoachPlanForChat coach, ApprovalService approvals) { this.coach = coach; this.approvals = approvals; }
    public static boolean action(String action) { return "adoptGrowthPlan".equals(action); }
    public static boolean recognizes(PlanStep step) { return step.agent() == ExpertType.COACH && action(String.valueOf(step.inputs().get("action"))); }
    static List<String> validate(Map<String,Object> args) {
        if (!args.keySet().equals(Set.of("taskTitles")) || !(args.get("taskTitles") instanceof List<?> titles)
                || titles.isEmpty() || titles.size() > GrowthTaskCatalog.TITLES.size()
                || titles.stream().anyMatch(t -> !(t instanceof String) || !GrowthTaskCatalog.TITLES.contains(t))
                || new HashSet<>(titles).size() != titles.size())
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择目录内已完成的成长任务（1–6 项，不重复）");
        return titles.stream().map(String.class::cast).toList();
    }
    public ApprovalService.Ticket request(String owner, String runId, PlanStep step) {
        var args = new LinkedHashMap<String,Object>();
        if (step.inputs().containsKey("taskTitles")) args.put("taskTitles", step.inputs().get("taskTitles"));
        var titles = validate(args);
        return approvals.request(owner, runId, "adoptGrowthPlan", Map.of("taskTitles", titles));
    }
    public Mono<ExpertResult> execute(String owner, PlanStep step, ApprovalService.Decision decision) {
        if (!"APPROVE".equals(decision.decision())) return Mono.error(new BusinessException(ErrorCode.APPROVAL_REJECTED,
                "TIMEOUT".equals(decision.decision()) ? "审批已超时，未同步成长任务" : "用户拒绝了该操作，未同步成长任务"));
        return Mono.fromCallable(() -> {
            var titles = validate(decision.args());
            coach.adoptGrowthPlan(owner, titles);
            return ExpertResult.text("已按用户批准将所选成长任务标记完成，并保留原有完成项：" + String.join("、", titles));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
