package com.offerpilot.agent.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.conversation.application.ConversationService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Approval waits. No thread or database transaction is held while waiting. Every write tool's
 * approval is also stored durably: if the chat stream goes away while one is pending, the in-memory
 * wait is dropped and the approval can still be decided later (see {@link DetachedApprovals}).
 */
@Service
public class ApprovalService {
    public record Decision(String decision, Map<String, Object> args, String reason) {}
    public static final Duration WINDOW = Duration.ofMinutes(10);
    private final ConversationService conversations;
    private final ApprovalStore store;
    private final com.offerpilot.career.application.CareerRecommendForChat careers;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public ApprovalService(ConversationService conversations) { this(conversations, null); }

    public ApprovalService(ConversationService conversations, ApprovalStore store) { this(conversations, store, null); }

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalService(ConversationService conversations, ApprovalStore store, com.offerpilot.career.application.CareerRecommendForChat careers) {
        this.conversations = conversations;
        this.store = store;
        this.careers = careers;
    }

    static boolean durable(String action) { return DetachedApprovals.durable(action); }

    public final class Ticket {
        private final String id = UUID.randomUUID().toString();
        private final String owner;
        private final String action;
        private final Map<String, Object> args;
        private final Instant expiresAt;
        private final Sinks.One<Decision> signal = Sinks.one();
        private final boolean durable;
        private String key;
        private boolean detached;
        private Decision resolved;

        private Ticket(String owner, String action, Map<String, Object> args, Duration window) {
            this.durable = store != null && (durable(action) || "generateAnalysis".equals(action));
            this.owner = owner;
            this.action = action;
            this.args = freeze(args);
            this.expiresAt = Instant.now().plus(window);
        }
        public String id() { return id; }
        public Map<String, Object> args() { return args; }
        public Instant expiresAt() { return expiresAt; }
        public Mono<Decision> await() {
            Duration remaining = Duration.between(Instant.now(), expiresAt);
            return signal.asMono().timeout(remaining.isNegative() ? Duration.ZERO : remaining, Mono.fromSupplier(this::expire))
                    .doOnCancel(() -> { if (durable && !"generateAnalysis".equals(action)) detach(); else expire(); });
        }
        /** The stream is gone; leave the stored approval pending for a later decision from the board. */
        private synchronized void detach() {
            if (resolved != null) return;
            detached = true;
            tickets.remove(key, this);
        }
        private synchronized Decision expire() {
            if (resolved == null) {
                if (durable) store.timeout(id);
                resolved = new Decision("TIMEOUT", args, "审批已超时");
                signal.tryEmitValue(resolved);
            }
            return resolved;
        }
        /** Null when the stream already detached; the caller then decides the stored copy instead. */
        private synchronized Decision decide(String owner, Decision body) {
            if (!this.owner.equals(owner)) throw new BusinessException(ErrorCode.RUN_NOT_FOUND);
            if (detached) return null;
            if (!Instant.now().isBefore(expiresAt)) expire();
            if (resolved != null) throw new BusinessException(ErrorCode.APPROVAL_EXPIRED, "审批已处理或已超时");
            if (body == null || !java.util.Set.of("APPROVE", "REJECT").contains(body.decision() == null ? "" : body.decision())
                    || (body.reason() != null && body.reason().length() > 2000))
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "审批决策无效");
            var finalArgs = mergeForDecision(action, args, body);
            if (durable && !store.claim(id, owner, "APPROVE".equals(body.decision()) ? "APPROVED" : "REJECTED", finalArgs, body.reason()))
                throw new BusinessException(ErrorCode.APPROVAL_EXPIRED, "审批已处理或已超时");
            resolved = new Decision(body.decision(), Map.copyOf(finalArgs), body.reason());
            signal.tryEmitValue(resolved);
            return resolved;
        }
    }

    public Ticket request(String owner, String runId, String action, Map<String, Object> args) {
        conversations.extendApprovalLease(owner, runId);
        return request(owner, runId, action, args, WINDOW);
    }

    // Duration overload keeps timeout tests deterministic without waiting ten minutes.
    Ticket request(String owner, String runId, String action, Map<String, Object> args, Duration window) {
        validate(action, args);
        Ticket ticket = new Ticket(owner, action, args, window);
        String key = runId + ":" + ticket.id();
        ticket.key = key;
        if (ticket.durable) store.insert(ticket.id(), owner, runId, action, ticket.args(), ticket.expiresAt());
        tickets.put(key, ticket);
        // Retain resolved tombstones for duplicate POSTs, then bound memory usage.
        Mono.delay(window.plus(Duration.ofMinutes(10))).subscribe(ignored -> tickets.remove(key, ticket));
        return ticket;
    }

    public Decision resolve(String owner, String runId, String approvalId, Decision body) {
        conversations.requireRunOwner(owner, runId);
        Ticket ticket = tickets.get(runId + ":" + approvalId);
        if (ticket == null) throw new BusinessException(ErrorCode.RUN_NOT_FOUND, "审批不存在");
        Decision decision = ticket.decide(owner, body);
        if (decision == null) throw new BusinessException(ErrorCode.APPROVAL_EXPIRED, "对话已中断，请到工作台的待确认操作中处理");
        return decision;
    }

    /** Decides a live wait if the chat stream still holds it; null means the caller must use the stored copy. */
    Decision resolveLive(String owner, String runId, String approvalId, Decision body) {
        Ticket ticket = tickets.get(runId + ":" + approvalId);
        return ticket == null ? null : ticket.decide(owner, body);
    }

    boolean live(String runId, String approvalId) { return tickets.containsKey(runId + ":" + approvalId); }

    /** Merges approver edits into stored args using the same editable-field rule as live approvals. */
    static Map<String, Object> merge(String action, Map<String, Object> args, Decision body) {
        if (body == null || !java.util.Set.of("APPROVE", "REJECT").contains(body.decision() == null ? "" : body.decision())
                || (body.reason() != null && body.reason().length() > 2000))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "审批决策无效");
        var finalArgs = new java.util.LinkedHashMap<>(args);
        if (body.args() != null && !body.args().isEmpty()) {
            var editable = editableFields(action);
            if (!editable.containsAll(body.args().keySet()))
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "审批包含不允许编辑的参数");
            finalArgs.putAll(body.args());
        }
        validate(action, finalArgs);
        if (MemoryChat.action(action) && !((String) args.get("fact")).contains((String) finalArgs.get("fact")))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "记忆编辑只能截取本轮用户原话");
        return finalArgs;
    }

    public static String alternative(String action, Map<String, Object> args) {
        return switch (action) {
            case "createApplication", "updateApplicationStatus" -> "可以先整理跟进草稿" + (args.containsKey("note") ? "（待核对备注：" + args.get("note") + "）" : "，核对投递阶段和下一步行动") + "，确认后再保存。";
            case "setCareerTarget", "adoptTargetCareer" -> "可以先比较「" + args.get("name") + "」的工作内容与个人偏好，再决定目标。";
            case "adoptGrowthPlan" -> "可以先核对所选成长任务的完成证据，再选择需要同步的项目。";
            case "applyPatches" -> "可以先预览所选简历修改，核对事实后逐条采纳。";
            case "saveStarStory" -> "可以先检查这道回答的背景、任务、行动和结果，再决定入库。";
            case "saveUserFact" -> "可以先在当前对话中保留这条信息，需要长期记忆时重新发起。";
            default -> "可以先在对话中练习面试答案、整理提纲，再决定是否保存到面试。";
        };
    }

    public static java.util.List<String> editableFields(String action) {
        return switch (action) {
            case "createApplication", "updateApplicationStatus" -> java.util.List.of("status", "note", "nextAction", "nextActionAt");
            case "setCareerTarget", "adoptTargetCareer" -> java.util.List.of("name");
            case "adoptGrowthPlan" -> java.util.List.of("taskTitles");
            case "applyPatches" -> java.util.List.of("accepted", "edited", "confirmed");
            case "saveStarStory" -> java.util.List.of("questionIndex");
            case "startInterview" -> java.util.List.of("role", "kind");
            case "continueInterview", "answerInterview" -> java.util.List.of("answer", "nextQuestion");
            case "saveUserFact" -> java.util.List.of("fact");
            default -> java.util.List.of();
        };
    }

    Map<String, Object> mergeForDecision(String action, Map<String, Object> original, Decision body) {
        var merged = merge(action, original, body);
        if (CareerWriteTools.action(action) && !java.util.Objects.equals(original.get("name"), merged.get("name"))) {
            if (careers == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "无法校验职业目录");
            var occupation = careers.occupation(null, (String) merged.get("name"));
            merged.put("occupationId", occupation.id());
            merged.put("name", occupation.name());
        }
        // Deep copy nested selections: caller mutation must not alter an approved/audited decision.
        return freeze(merged);
    }

    @SuppressWarnings("unchecked")
    private static <T> T freeze(T value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new java.util.LinkedHashMap<Object, Object>();
            map.forEach((key, item) -> copy.put(key, freeze(item)));
            return (T) java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof java.util.List<?> list) return (T) list.stream().map(ApprovalService::freeze).toList();
        return value;
    }

    private static void validate(String action, Map<String, Object> args) {
        if("generateAnalysis".equals(action)) {
            if(!args.keySet().equals(java.util.Set.of("expert","inputs","resources"))
                    || !(args.get("expert") instanceof String e) || !(args.get("inputs") instanceof Map<?,?> inputs)
                    || !(args.get("resources") instanceof Map<?,?> resources)
                    || !java.util.Set.of("jobId","resumeId","resumeVersionId").containsAll(resources.keySet()))
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,"分析审批参数无效");
            try {
                @SuppressWarnings("unchecked") var values=(Map<String,Object>)inputs;
                com.offerpilot.agent.tools.ToolContracts.validate(new com.offerpilot.agent.domain.PlanStep("analysis","analysis",
                        com.offerpilot.agent.domain.ExpertType.valueOf(e),values,java.util.List.of()));
            } catch(IllegalArgumentException ex) { throw new BusinessException(ErrorCode.VALIDATION_FAILED,"分析审批参数无效"); }
            return;
        }
        if (MemoryChat.action(action)) com.offerpilot.agent.tools.ToolContracts.validateMemory(args);
        if (CareerWriteTools.action(action)) CareerWriteTools.validate(action, args);
        else if (InterviewWriteTools.action(action)) InterviewWriteTools.validate(action, args);
        else if (CoachWriteTools.action(action)) CoachWriteTools.validate(args);
        else if (ResumePatchWriteTools.action(action)) ResumePatchWriteTools.validate(args);
        else if (MemoryChat.action(action)) MemoryChat.validate(args);
        else if (StarStoryWriteTools.action(action)) StarStoryWriteTools.validate(args);
        else BoardWriteTools.validate(action, args);
        com.offerpilot.agent.tools.ToolContracts.validateApproved(action,args);
    }
}
