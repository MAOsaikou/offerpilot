package com.offerpilot.resume.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.resume.application.ResumePatchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/resumes/{id}/versions/{vid}")
public class ResumePatchController {
    private final ResumePatchService patches;
    private final CurrentUser users;
    public ResumePatchController(ResumePatchService patches, CurrentUser users) {
        this.patches = patches; this.users = users;
    }
    @GetMapping("/patches/{batchId}")
    public ApiResponse<?> get(@PathVariable String id, @PathVariable String vid, @PathVariable String batchId, HttpServletRequest r) {
        return ApiResponse.ok(patches.get(users.require(r.getHeader("Authorization")), id, vid, batchId), TraceIdFilter.current(r));
    }
    @PostMapping("/apply-patches")
    public ApiResponse<?> apply(@PathVariable String id, @PathVariable String vid, @RequestBody JsonNode body, HttpServletRequest r) {
        return ApiResponse.ok(patches.apply(users.require(r.getHeader("Authorization")), id, vid, body), TraceIdFilter.current(r));
    }
}
