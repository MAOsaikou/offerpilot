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
        for (String key : Set.of("jobId", "applicationId", "status", "note", "nextAction", "nextActionAt"))
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
        for (String field : Set.of("nextAction", "nextActionAt"))
            if (args.containsKey(field) && !(args.get(field) instanceof String)) throw invalid("行动参数须为文本");
        if (args.containsKey("nextAction") || args.containsKey("nextActionAt"))
            ApplicationService.validate(new ApplicationService.Patch((String) args.get("status"), (String) args.get("note"),
                    (String) args.get("nextAction"), (String) args.get("nextActionAt")));
        if (action.equals("updateApplicationStatus") && !args.containsKey("status") && !args.containsKey("note")
                && !args.containsKey("nextAction") && !args.containsKey("nextActionAt"))
            throw invalid("请提供状态、备注或下一步行动");
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED, message); }

    private static String statusLabel(String status) {
        return switch (status) {
            case "TODO" -> "待投递";
            case "APPLIED" -> "已投递";
            case "INTERVIEW" -> "面试中";
            case "OFFER" -> "已获 Offer";
            case "CLOSED" -> "已结束";
            default -> "未知";
        };
    }

    public Mono<ExpertResult> detail(String owner, String id) {
        return Mono.fromCallable(() -> {
            var detail = applications.detail(owner, id);
            var app = detail.application();
            return ExpertResult.text("求职记录：" + app.job().path("company").asText() + " / "
                    + app.job().path("title").asText() + "，阶段 " + statusLabel(app.status()) + "，下一步 " + app.nextAction()
                    + "，日期 " + (app.nextActionAt()==null?"未安排":app.nextActionAt()) + "，备注：" + app.note()
                    + "。最新评估：" + (detail.latestMatch()==null?"暂无成功匹配报告":"等级 " + detail.latestMatch().grade() + "，主要差距：" + String.join("、", detail.latestMatch().topGaps()))
                    + "。最近跟进事件：" + detail.timeline().stream().skip(Math.max(0,detail.timeline().size()-5)).map(e -> e.createdAt() + "：" + e.detail()).toList()
                    + "。" + detail.linkageNote());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ExpertResult> execute(String owner, String runId, PlanStep step, ApprovalService.Decision decision) {
        if (!"APPROVE".equals(decision.decision())) return Mono.error(new BusinessException(ErrorCode.APPROVAL_REJECTED,
                "TIMEOUT".equals(decision.decision()) ? "审批已超时" : "用户拒绝了该操作"));
        return Mono.fromCallable(() -> {
            String action = (String) step.inputs().get("action");
            var args = decision.args();
            validate(action, args);
            boolean create = action.equals("createApplication");
            var patch = new ApplicationService.Patch((String) args.get("status"), (String) args.get("note"),
                    (String) args.get("nextAction"), (String) args.get("nextActionAt"));
            var saved = create ? applications.createWithPatch(owner, (String) args.get("jobId"), patch, runId)
                    : applications.updateWithPatch(owner, (String) args.get("applicationId"), patch, "AGENT", runId);
            var application = new LinkedHashMap<String, Object>();
            application.put("id", saved.id());
            application.put("company", saved.job().path("company").asText());
            application.put("title", saved.job().path("title").asText());
            application.put("status", saved.status());
            application.put("note", saved.note());
            application.put("nextAction", saved.nextAction());
            application.put("nextActionAt", saved.nextActionAt());
            var changedFields = new LinkedHashMap<String, Object>();
            for (String field : java.util.List.of("status", "note", "nextAction", "nextActionAt"))
                if (args.containsKey(field)) changedFields.put(field, application.get(field));
            return ExpertResult.card("已按用户批准的参数" + (create ? "创建" : "更新") + "投递记录：" + saved.job().path("company").asText() + " / " + saved.job().path("title").asText() + "，阶段：" + statusLabel(saved.status()),
                    runId + "_" + step.id(), "board_update", Map.of("application", application, "change", create ? "CREATED" : "UPDATED", "changedFields", changedFields));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
