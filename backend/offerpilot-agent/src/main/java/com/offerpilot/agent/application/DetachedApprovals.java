package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.board.application.ApplicationService;
import com.offerpilot.career.application.CareerRecommendForChat;
import com.offerpilot.career.application.CoachPlanForChat;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.interview.application.InterviewCoachForChat;
import com.offerpilot.interview.application.StarStoryService;
import com.offerpilot.job.application.JobService;
import com.offerpilot.memory.application.MemoryCandidate;
import com.offerpilot.memory.application.UserMemoryService;
import com.offerpilot.resume.application.ResumePatchService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * BD-02 recovery for every agent write tool: writes proposed in chat whose stream is gone (reload,
 * network drop, restart) stay pending until their original deadline and can be approved or rejected
 * later from the board or the workbench. Nothing is written without that decision, and claim + write
 * commit together, so a failed write leaves the approval pending for another try.
 */
@Service
public class DetachedApprovals {
    /** Non-board write actions that are stored durably (board actions live in {@link BoardWriteTools#ACTIONS}). */
    public static final Set<String> AGENT_ACTIONS = Set.of("setCareerTarget", "adoptTargetCareer", "adoptGrowthPlan",
            "applyPatches", StarStoryWriteTools.ACTION, "startInterview", "continueInterview", "answerInterview",
            "completeInterview", MemoryChat.ACTION);

    static boolean durable(String action) { return BoardWriteTools.ACTIONS.contains(action) || AGENT_ACTIONS.contains(action); }

    public record Pending(String id, String runId, String action, Map<String, Object> args, Instant expiresAt, Instant createdAt,
                          String company, String title, String currentStatus, String summary, List<String> editableFields) {}
    public record Result(String decision, boolean applied, Object application, String summary) {}

    /** Write services for the non-board tools, grouped to keep the constructor readable. */
    @org.springframework.stereotype.Component
    public static class Writers {
        final CareerRecommendForChat careers;
        final CoachPlanForChat coach;
        final ResumePatchService patches;
        final StarStoryService stories;
        final InterviewCoachForChat interviews;
        final UserMemoryService memories;
        public Writers(CareerRecommendForChat careers, CoachPlanForChat coach, ResumePatchService patches, StarStoryService stories,
                       InterviewCoachForChat interviews, UserMemoryService memories) {
            this.careers = careers; this.coach = coach; this.patches = patches; this.stories = stories;
            this.interviews = interviews; this.memories = memories;
        }
    }

    private final ApprovalService approvals;
    private final ApprovalStore store;
    private final ApplicationService applications;
    private final JobService jobs;
    private final Writers writers;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public DetachedApprovals(ApprovalService approvals, ApprovalStore store, ApplicationService applications, JobService jobs,
                             Writers writers, ObjectMapper json, TransactionTemplate tx) {
        this.approvals = approvals;
        this.store = store;
        this.applications = applications;
        this.jobs = jobs;
        this.writers = writers;
        this.json = json;
        this.tx = tx;
    }

    public List<Pending> pending(String owner) {
        var actions = Stream.concat(BoardWriteTools.ACTIONS.stream(), AGENT_ACTIONS.stream()).toList();
        var rows = store.pending(owner, actions).stream().filter(row -> !approvals.live(row.runId(), row.id())).toList();
        if (rows.isEmpty()) return List.of();
        var board = new java.util.HashMap<String, ApplicationService.Application>();
        if (rows.stream().anyMatch(row -> BoardWriteTools.ACTIONS.contains(row.action())))
            applications.list(owner).forEach(app -> board.put(app.id(), app));
        return rows.stream().map(row -> {
            if (!BoardWriteTools.ACTIONS.contains(row.action()))
                return new Pending(row.id(), row.runId(), row.action(), row.args(), row.expiresAt(), row.createdAt(), null, null, null, summary(row.action(), row.args()), ApprovalService.editableFields(row.action()));
            String company = null, title = null, status = null;
            if ("createApplication".equals(row.action())) {
                try {
                    var job = jobs.get(owner, (String) row.args().get("jobId"));
                    company = job.path("company").asText(null);
                    title = job.path("title").asText(null);
                } catch (BusinessException missing) { /* job gone: approving will fail visibly */ }
            } else {
                var app = board.get((String) row.args().get("applicationId"));
                if (app != null) {
                    company = app.job().path("company").asText(null);
                    title = app.job().path("title").asText(null);
                    status = app.status();
                }
            }
            return new Pending(row.id(), row.runId(), row.action(), row.args(), row.expiresAt(), row.createdAt(), company, title, status, null, ApprovalService.editableFields(row.action()));
        }).toList();
    }

    /** What the user is approving, in their words; built from the pinned args only. */
    static String summary(String action, Map<String, Object> args) {
        return switch (action) {
            case "setCareerTarget", "adoptTargetCareer" -> "把职业目标设为「" + args.get("name") + "」";
            case "adoptGrowthPlan" -> "把成长任务标记为已完成：" + String.join("、", ((List<?>) args.get("taskTitles")).stream().map(String::valueOf).toList());
            case "applyPatches" -> "采纳 " + ((List<?>) args.get("accepted")).size() + " 条简历修改建议，生成新版本（原版本保留）";
            case StarStoryWriteTools.ACTION -> "把模拟面试第 " + (((Number) args.get("questionIndex")).intValue() + 1) + " 题的回答保存为 STAR 故事";
            case "startInterview" -> "开始一场「" + args.get("role") + "」" + args.get("kind") + "模拟面试";
            case "continueInterview", "answerInterview" -> "保存面试答案" + (Boolean.TRUE.equals(args.get("nextQuestion")) ? "并进入下一题" : "")
                    + "：「" + clip((String) args.get("answer"), 40) + "」";
            case "completeInterview" -> "结束模拟面试并生成评分卡";
            case MemoryChat.ACTION -> "记住：「" + args.get("fact") + "」";
            default -> "执行 Agent 提议的操作";
        };
    }

    private static String clip(String text, int max) {
        return text.codePointCount(0, text.length()) <= max ? text : text.substring(0, text.offsetByCodePoints(0, max)) + "…";
    }

    public Result resolve(String owner, String approvalId, ApprovalService.Decision body) {
        var row = store.find(owner, approvalId).filter(r -> durable(r.action()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND, "审批不存在"));
        // Still waiting in an open chat stream: decide there so the conversation continues normally.
        var live = approvals.resolveLive(owner, row.runId(), approvalId, body);
        if (live != null) return new Result(live.decision(), false, null, null);
        var finalArgs = approvals.mergeForDecision(row.action(), row.args(), body);
        boolean approve = "APPROVE".equals(body.decision());
        return tx.execute(status -> {
            if (!store.claim(approvalId, owner, approve ? "APPROVED" : "REJECTED", finalArgs, body.reason()))
                throw new BusinessException(ErrorCode.APPROVAL_EXPIRED, "审批已处理或已超时");
            if (!approve) return new Result("REJECT", false, null, "已拒绝，本次操作未执行。" + ApprovalService.alternative(row.action(), finalArgs));
            if (BoardWriteTools.ACTIONS.contains(row.action())) {
                var card = BoardWriteTools.apply(applications, owner, row.runId(), row.action(), finalArgs, row.runId() + "_" + approvalId).cards().get(0);
                return new Result("APPROVE", true, ((Map<?, ?>) card.payload()).get("application"), null);
            }
            return new Result("APPROVE", true, null, apply(owner, row.runId(), row.action(), finalArgs));
        });
    }

    private String apply(String owner, String runId, String action, Map<String, Object> args) {
        switch (action) {
            case "setCareerTarget", "adoptTargetCareer" -> {
                var saved = writers.careers.adopt(owner, (String) args.get("occupationId"), (String) args.get("name"));
                return "职业目标已设为：" + saved.target();
            }
            case "adoptGrowthPlan" -> {
                var titles = CoachWriteTools.validate(args);
                writers.coach.adoptGrowthPlan(owner, titles);
                return "已将所选成长任务标记完成：" + String.join("、", titles);
            }
            case "applyPatches" -> {
                var saved = writers.patches.apply(owner, (String) args.get("resumeId"), (String) args.get("resumeVersionId"),
                        json.valueToTree(ResumePatchWriteTools.body(args)));
                return "已采纳简历修改，生成新版本 V" + saved.version().versionNo() + "，原版本保留可查看。";
            }
            case StarStoryWriteTools.ACTION -> {
                var story = writers.stories.saveFromSession(owner, (String) args.get("sessionId"), ((Number) args.get("questionIndex")).intValue());
                return "已保存 STAR 故事：" + story.title();
            }
            case "startInterview" -> {
                var turn = args.containsKey("jobId")
                        ? writers.interviews.startSession(owner, (String) args.get("role"), (String) args.get("kind"), (String) args.get("jobId"))
                        : writers.interviews.startSession(owner, (String) args.get("role"), (String) args.get("kind"));
                return "面试已创建，可在模拟面试中回答第一题：" + turn.question();
            }
            case "continueInterview", "answerInterview" -> {
                var turn = writers.interviews.continueSession(owner, (String) args.get("sessionId"), (String) args.get("answer"),
                        (Boolean) args.get("nextQuestion"), (String) args.get("expectedUpdatedAt"));
                return "答案已保存。" + turn.note();
            }
            case "completeInterview" -> {
                writers.interviews.completeSession(owner, (String) args.get("sessionId"), (String) args.get("expectedUpdatedAt"));
                return "面试已标记完成，可在模拟面试中查看评分卡。";
            }
            case MemoryChat.ACTION -> {
                // Still grounded on the run's own user message; only the "run still streaming" requirement is lifted.
                var candidate = new MemoryCandidate.Candidate(UserMemoryService.Kind.valueOf((String) args.get("kind")),
                        (String) args.get("fact"), UserMemoryService.Source.valueOf((String) args.get("source")));
                writers.memories.saveApprovedExcerpt(owner, runId, candidate, false);
                return "记忆已保存，可在设置中管理。";
            }
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的审批操作");
        }
    }
}
