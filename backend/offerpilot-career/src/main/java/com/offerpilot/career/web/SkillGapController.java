package com.offerpilot.career.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.career.application.SkillGapService;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/career/skill-gap")
public class SkillGapController {
    private final CurrentUser users;
    private final SkillGapService service;
    public SkillGapController(CurrentUser users, SkillGapService service) { this.users = users; this.service = service; }
    @GetMapping public ApiResponse<SkillGapService.Report> analyze(@RequestParam(required=false) String occupationId,
            @RequestParam(required=false) String jobId, @RequestParam(required=false) String resumeId,
            @RequestParam(required=false) String versionId, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(service.analyze(users.require(request.getHeader("Authorization")), occupationId, jobId, resumeId, versionId), TraceIdFilter.current(request));
    }
}
