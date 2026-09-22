package com.offerpilot.job.web;

import com.offerpilot.job.application.*;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {
    private final JobService jobs;
    private final JobMatchService matches;
    private final CurrentUser users;
    public JobController(JobService jobs,JobMatchService matches,CurrentUser users) { this.jobs=jobs; this.matches=matches; this.users=users; }
    private String owner(HttpServletRequest request) { return users.require(request.getHeader("Authorization")); }
    private <T> ApiResponse<T> ok(T value,HttpServletRequest request) { return ApiResponse.ok(value,TraceIdFilter.current(request)); }
    @GetMapping("/sites") public ApiResponse<?> sites(HttpServletRequest r) { owner(r); return ok(jobs.sites(),r); }
    @GetMapping public ApiResponse<?> search(@RequestParam String query,@RequestParam(defaultValue="") String site,
        @RequestParam(defaultValue="campus") String nature,@RequestParam(defaultValue="") String location,HttpServletRequest r) {
        owner(r); return ok(jobs.search(query,site,nature,location),r);
    }
    @GetMapping("/{id}") public ApiResponse<?> get(@PathVariable String id,HttpServletRequest r) { owner(r); return ok(jobs.get(id),r); }
    @PostMapping("/{id}/refresh") public ApiResponse<?> refresh(@PathVariable String id,HttpServletRequest r) { owner(r); return ok(jobs.detail(id,true),r); }
    public record MatchRequest(@NotBlank @Size(max=100) String resumeId,@NotBlank @Size(max=100) String resumeVersionId,
                               @NotBlank @Pattern(regexp="[a-zA-Z0-9-]{16,80}") String requestId) {}
    @PostMapping("/{id}/matches") public ApiResponse<?> match(@PathVariable String id,@Valid @RequestBody MatchRequest body,HttpServletRequest r) {
        return ok(matches.create(owner(r),id,body.resumeId(),body.resumeVersionId(),body.requestId()),r);
    }
    @GetMapping("/{id}/matches") public ApiResponse<?> history(@PathVariable String id,HttpServletRequest r) { return ok(matches.list(owner(r),id),r); }
    @GetMapping("/matches/{id}") public ApiResponse<?> report(@PathVariable String id,HttpServletRequest r) { return ok(matches.get(owner(r),id),r); }
}
