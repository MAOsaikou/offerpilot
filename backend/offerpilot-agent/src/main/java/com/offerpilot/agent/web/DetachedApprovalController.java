package com.offerpilot.agent.web;

import com.offerpilot.agent.application.ApprovalService;
import com.offerpilot.agent.application.DetachedApprovals;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Agent writes (board, career, resume, interview, growth, memory) proposed by the agent that still await the user's decision after the chat stream ended. */
@RestController
@RequestMapping("/api/v1/agent/approvals")
public class DetachedApprovalController {
    private final DetachedApprovals approvals;
    private final CurrentUser users;
    public DetachedApprovalController(DetachedApprovals approvals, CurrentUser users) { this.approvals = approvals; this.users = users; }

    @GetMapping("/pending")
    public ApiResponse<List<DetachedApprovals.Pending>> pending(HttpServletRequest request) {
        return ApiResponse.ok(approvals.pending(users.require(request.getHeader("Authorization"))), TraceIdFilter.current(request));
    }

    @PostMapping("/{approvalId}")
    public ApiResponse<DetachedApprovals.Result> decide(@PathVariable String approvalId, @RequestBody ApprovalService.Decision body,
                                                             HttpServletRequest request) {
        return ApiResponse.ok(approvals.resolve(users.require(request.getHeader("Authorization")), approvalId, body), TraceIdFilter.current(request));
    }
}
