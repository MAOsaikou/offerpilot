package com.offerpilot.career.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.career.application.InterviewLearningPlans;
import com.offerpilot.career.application.InterviewPlanUpdates;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/career/learning-plans")
public class InterviewLearningPlanController {
    @ModelAttribute
    void preventCaching(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    private final InterviewLearningPlans plans;
    private final CurrentUser users;
    private final InterviewPlanUpdates updates;
    public InterviewLearningPlanController(InterviewLearningPlans plans, CurrentUser users, InterviewPlanUpdates updates) { this.plans=plans; this.users=users; this.updates=updates; }
    private String owner(HttpServletRequest request) { return users.require(request.getHeader("Authorization")); }
    @GetMapping
    public ApiResponse<?> list(HttpServletRequest request) { return ApiResponse.ok(plans.list(owner(request)), TraceIdFilter.current(request)); }
    @PostMapping("/from-interview/{reportId}")
    public ApiResponse<?> create(@PathVariable String reportId, HttpServletRequest request) {
        return ApiResponse.ok(plans.create(owner(request), reportId), TraceIdFilter.current(request));
    }
    public record Confirmation(@jakarta.validation.constraints.Pattern(regexp="[a-f0-9]{64}")
                               @jakarta.validation.constraints.NotNull String fingerprint) {}
    @GetMapping("/updates/from-interview/{reportId}")
    public ApiResponse<?> preview(@PathVariable String reportId, HttpServletRequest request) {
        return ApiResponse.ok(updates.preview(owner(request),reportId),TraceIdFilter.current(request));
    }
    @PostMapping("/{id}/updates/from-interview/{reportId}/confirm")
    public ApiResponse<?> confirm(@PathVariable String id, @PathVariable String reportId,
                                 @jakarta.validation.Valid @RequestBody Confirmation body, HttpServletRequest request) {
        return ApiResponse.ok(updates.confirm(owner(request),reportId,id,body.fingerprint()),TraceIdFilter.current(request));
    }
    @GetMapping(value="/{id}/calendar.ics", produces="text/calendar;charset=UTF-8")
    public ResponseEntity<byte[]> calendar(@PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=learning-plan.ics")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(new MediaType("text", "calendar", StandardCharsets.UTF_8))
                .body(plans.calendar(owner(request), id).getBytes(StandardCharsets.UTF_8));
    }
}
