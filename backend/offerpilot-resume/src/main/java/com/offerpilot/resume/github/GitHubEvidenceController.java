package com.offerpilot.resume.github;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.resume.application.ResumeService;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/resumes/github")
public class GitHubEvidenceController {
    private final CurrentUser users;
    private final GitHubEvidenceService service;
    public GitHubEvidenceController(CurrentUser users, GitHubEvidenceService service) { this.users = users; this.service = service; }
    // Deliberately no record: its generated toString would disclose a submitted PAT.
    public static class Settings {
        public String username;
        public String token;
        public boolean removeToken;
        @Override public String toString() { return "GitHubSettings[redacted]"; }
    }
    public record Adopt(String resumeId, String baseVersionId, String snapshotId, String repository, boolean confirmed) {}
    private String owner(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return users.require(request.getHeader("Authorization"));
    }
    @GetMapping public ApiResponse<GitHubEvidenceService.View> get(HttpServletRequest req, HttpServletResponse res) {
        return ApiResponse.ok(service.get(owner(req, res)), TraceIdFilter.current(req));
    }
    @PutMapping public ApiResponse<GitHubEvidenceService.View> save(@RequestBody Settings body, HttpServletRequest req, HttpServletResponse res) {
        return ApiResponse.ok(service.save(owner(req, res), body.username, body.token, body.removeToken), TraceIdFilter.current(req));
    }
    @DeleteMapping public ApiResponse<Void> delete(HttpServletRequest req, HttpServletResponse res) {
        service.delete(owner(req, res)); return ApiResponse.ok(null, TraceIdFilter.current(req));
    }
    @PostMapping("/refresh") public ApiResponse<GitHubEvidenceService.View> refresh(@RequestParam(defaultValue="false") boolean force, HttpServletRequest req, HttpServletResponse res) {
        return ApiResponse.ok(service.refresh(owner(req, res), force), TraceIdFilter.current(req));
    }
    @PostMapping("/adopt") public ApiResponse<ResumeService.Resume> adopt(@RequestBody Adopt body, HttpServletRequest req, HttpServletResponse res) {
        return ApiResponse.ok(service.adopt(owner(req, res), body.resumeId(), body.baseVersionId(), body.snapshotId(), body.repository(), body.confirmed()), TraceIdFilter.current(req));
    }
}
