package com.offerpilot.agent.application;

import com.offerpilot.board.application.ApplicationService;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.job.application.JobService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * BD-02 recovery: board writes the agent proposed whose chat stream is gone (reload, network drop,
 * restart). They stay pending until their original deadline and can be approved or rejected from
 * the board; nothing is written without that decision.
 */
@Service
public class DetachedBoardApprovals {
    public record Pending(String id, String runId, String action, Map<String, Object> args, Instant expiresAt, Instant createdAt,
                          String company, String title, String currentStatus) {}
    public record Result(String decision, boolean applied, Object application) {}

    private final ApprovalService approvals;
    private final ApprovalStore store;
    private final ApplicationService applications;
    private final JobService jobs;
    private final TransactionTemplate tx;

    public DetachedBoardApprovals(ApprovalService approvals, ApprovalStore store, ApplicationService applications, JobService jobs,
                                  TransactionTemplate tx) {
        this.approvals = approvals;
        this.store = store;
        this.applications = applications;
        this.jobs = jobs;
        this.tx = tx;
    }

    public List<Pending> pending(String owner) {
        var rows = store.pending(owner, List.copyOf(BoardWriteTools.ACTIONS)).stream()
                .filter(row -> !approvals.live(row.runId(), row.id())).toList();
        if (rows.isEmpty()) return List.of();
        var board = new java.util.HashMap<String, ApplicationService.Application>();
        applications.list(owner).forEach(app -> board.put(app.id(), app));
        return rows.stream().map(row -> {
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
            return new Pending(row.id(), row.runId(), row.action(), row.args(), row.expiresAt(), row.createdAt(), company, title, status);
        }).toList();
    }

    public Result resolve(String owner, String approvalId, ApprovalService.Decision body) {
        var row = store.find(owner, approvalId).filter(r -> BoardWriteTools.ACTIONS.contains(r.action()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND, "审批不存在"));
        // Still waiting in an open chat stream: decide there so the conversation continues normally.
        var live = approvals.resolveLive(owner, row.runId(), approvalId, body);
        if (live != null) return new Result(live.decision(), false, null);
        var finalArgs = ApprovalService.merge(row.action(), row.args(), body);
        boolean approve = "APPROVE".equals(body.decision());
        // Claim and write commit together: a failed write leaves the approval pending for another try.
        return tx.execute(status -> {
            if (!store.claim(approvalId, owner, approve ? "APPROVED" : "REJECTED", finalArgs, body.reason()))
                throw new BusinessException(ErrorCode.APPROVAL_EXPIRED, "审批已处理或已超时");
            if (!approve) return new Result("REJECT", false, null);
            var card = BoardWriteTools.apply(applications, owner, row.runId(), row.action(), finalArgs, row.runId() + "_" + approvalId).cards().get(0);
            return new Result("APPROVE", true, ((Map<?, ?>) card.payload()).get("application"));
        });
    }
}
