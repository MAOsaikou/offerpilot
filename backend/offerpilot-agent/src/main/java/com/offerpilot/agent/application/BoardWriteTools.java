package com.offerpilot.agent.application;

import com.offerpilot.agent.domain.PlanStep;
import com.offerpilot.agent.experts.ExpertResult;
import com.offerpilot.board.application.ApplicationService;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class BoardWriteTools {
    private final ApplicationService applications;
    private final ApprovalService approvals;
    public BoardWriteTools(ApplicationService applications, ApprovalService approvals) {
        this.applications = applications;
        this.approvals = approvals;
    }
    public static boolean recognizes(PlanStep step) {
        return step.agent() == com.offerpilot.agent.domain.ExpertType.JOB
                && Set.of("createApplication", "updateApplicationStatus").contains(String.valueOf(step.inputs().get("action")));
    }
    public ApprovalService.Ticket request(String owner, String runId, PlanStep step) {
        String action = (String) step.inputs().get("action");
        var args = new LinkedHashMap<String, Object>();
        for (String key : Set.of("jobId", "applicationId", "status", "note"))
            if (step.inputs().containsKey(key)) args.put(key, step.inputs().get(key));
        if (action.equals("createApplication") && !args.containsKey("status")) args.put("status", "TODO");
        validate(action, args);
        return approvals.request(owner, runId, action, args);
    }
    static void validate(String action, Map<String, Object> args) {
        if (!Set.of("createApplication", "updateApplicationStatus").contains(action)) throw invalid("未知看板操作");
        String key = action.equals("createApplication") ? "jobId" : "applicationId";
        if (!(args.get(key) instanceof String id) || id.isBlank() || id.length() > 80)
            throw invalid("缺少有效的" + key);
        if (args.containsKey("status") && (!(args.get("status") instanceof String status)
                || !Set.of("TODO", "APPLIED", "INTERVIEW", "OFFER", "CLOSED").contains(status)))
            throw invalid("状态仅支持 TODO/APPLIED/INTERVIEW/OFFER/CLOSED");
        if (args.containsKey("note") && (!(args.get("note") instanceof String note)
                || note.codePointCount(0, note.length()) > 2000)) throw invalid("备注须为不超过2000字的文本");
        if (action.equals("updateApplicationStatus") && !args.containsKey("status") && !args.containsKey("note"))
            throw invalid("请提供状态或备注");
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED, message); }

    public Mono<ExpertResult> execute(String owner, String runId, PlanStep step, ApprovalService.Decision decision) {
        if (!"APPROVE".equals(decision.decision())) return Mono.error(new BusinessException(ErrorCode.APPROVAL_REJECTED,
                "TIMEOUT".equals(decision.decision()) ? "审批已超时" : "用户拒绝了该操作"));
        return Mono.fromCallable(() -> {
            String action = (String) step.inputs().get("action");
            var args = decision.args();
            validate(action, args);
            boolean create = action.equals("createApplication");
            var saved = create ? applications.create(owner, (String) args.get("jobId"), (String) args.get("status"), (String) args.get("note"))
                    : applications.update(owner, (String) args.get("applicationId"), (String) args.get("status"), (String) args.get("note"));
            var application = new LinkedHashMap<String, Object>();
            application.put("id", saved.id());
            application.put("company", saved.job().path("company").asText());
            application.put("title", saved.job().path("title").asText());
            application.put("status", saved.status());
            application.put("nextActionAt", null);
            return ExpertResult.card("已按用户批准的参数" + (create ? "创建" : "更新") + "投递记录：" + saved.id() + "，状态：" + saved.status(),
                    runId + "_" + step.id(), "board_update", Map.of("application", application, "change", create ? "CREATED" : "UPDATED"));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
