package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.ExpertType;
import com.offerpilot.agent.domain.PlanStep;
import com.offerpilot.agent.experts.ExpertResult;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.resume.application.ResumePatchService;
import com.offerpilot.resume.application.ResumePatches;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

@Component
public class ResumePatchWriteTools {
    private static final Set<String> KEYS = Set.of("resumeId", "resumeVersionId", "batchId", "accepted", "edited", "confirmed");
    private static final ObjectMapper VALIDATOR = new ObjectMapper();
    private final ResumePatchService patches;
    private final ApprovalService approvals;
    private final ObjectMapper json;

    public ResumePatchWriteTools(ResumePatchService patches, ApprovalService approvals, ObjectMapper json) {
        this.patches = patches; this.approvals = approvals; this.json = json;
    }
    public static boolean action(String action) { return "applyPatches".equals(action); }
    public static boolean recognizes(PlanStep step) {
        return step.agent() == ExpertType.RESUME && action(String.valueOf(step.inputs().get("action")));
    }
    public static void validate(Map<String, Object> args) {
        if (args == null || !KEYS.containsAll(args.keySet())) throw invalid();
        for (String key : List.of("resumeId", "resumeVersionId", "batchId")) {
            int limit = key.equals("batchId") ? 100 : 80;
            if (!(args.get(key) instanceof String id) || !id.matches("[A-Za-z0-9_-]{1," + limit + "}")) throw invalid();
        }
        if (!(args.get("accepted") instanceof List<?>)
                || args.containsKey("confirmed") && !(args.get("confirmed") instanceof List<?>)
                || args.containsKey("edited") && !(args.get("edited") instanceof Map<?, ?>)) throw invalid();
        ResumePatches.decision(VALIDATOR.valueToTree(body(args)));
    }
    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择有效的简历、版本和补丁批次，并核对采纳项");
    }
    private static Map<String, Object> body(Map<String, Object> args) {
        var body = new LinkedHashMap<String, Object>(args);
        body.remove("resumeId"); body.remove("resumeVersionId");
        return body;
    }
    /** Called only with the selections copied from caller context by ResumeIntentParser. */
    public ApprovalService.Ticket request(String owner, String runId, PlanStep step) {
        var args = new LinkedHashMap<String, Object>(step.inputs());
        args.remove("action");
        validate(args);
        // Pin nested selections too: later caller edits cannot change a pending approval.
        args.put("accepted", List.copyOf((List<?>) args.get("accepted")));
        if (args.containsKey("confirmed")) args.put("confirmed", List.copyOf((List<?>) args.get("confirmed")));
        if (args.containsKey("edited")) {
            var edits = new LinkedHashMap<String, Object>();
            ((Map<?, ?>) args.get("edited")).forEach((id, value) -> edits.put((String) id, Map.copyOf((Map<?, ?>) value)));
            args.put("edited", Map.copyOf(edits));
        }
        return approvals.request(owner, runId, "applyPatches", args);
    }
    public Mono<ExpertResult> execute(String owner, String runId, PlanStep step, ApprovalService.Decision decision) {
        if (!"APPROVE".equals(decision.decision())) return Mono.error(new BusinessException(ErrorCode.APPROVAL_REJECTED,
                "TIMEOUT".equals(decision.decision()) ? "审批已超时，未采纳简历补丁" : "用户拒绝了该操作，未采纳简历补丁"));
        return Mono.fromCallable(() -> {
            validate(decision.args());
            var saved = patches.apply(owner, (String) decision.args().get("resumeId"),
                    (String) decision.args().get("resumeVersionId"), json.valueToTree(body(decision.args())));
            return ExpertResult.text("已按用户批准采纳所选简历补丁，生成新版本 V" + saved.version().versionNo()
                    + "，原版本保留可查看。");
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
