package com.offerpilot.agent.web;

import com.offerpilot.agent.application.ApprovalService;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agent/runs/{runId}/approvals")
public class ApprovalController {
    private final ApprovalService approvals;
    private final CurrentUser users;
    public ApprovalController(ApprovalService approvals, CurrentUser users) { this.approvals = approvals; this.users = users; }
    @PostMapping("/{approvalId}")
    public ApiResponse<ApprovalService.Decision> decide(@PathVariable String runId, @PathVariable String approvalId,
            @RequestBody ApprovalService.Decision body, HttpServletRequest request) {
        var result = approvals.resolve(users.require(request.getHeader("Authorization")), runId, approvalId, body);
        return ApiResponse.ok(result, TraceIdFilter.current(request));
    }
}
