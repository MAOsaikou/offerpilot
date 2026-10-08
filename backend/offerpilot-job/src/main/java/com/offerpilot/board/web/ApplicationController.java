package com.offerpilot.board.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.board.application.ApplicationService;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {
    private final ApplicationService applications;
    private final CurrentUser users;
    public ApplicationController(ApplicationService applications, CurrentUser users) { this.applications=applications; this.users=users; }
    private String owner(HttpServletRequest r) { return users.require(r.getHeader("Authorization")); }
    private <T> ApiResponse<T> ok(T value,HttpServletRequest r) { return ApiResponse.ok(value,TraceIdFilter.current(r)); }
    public record CreateRequest(@NotBlank @Size(max=100) String jobId) {}
    public record UpdateRequest(String status, String note) {}
    @GetMapping public ApiResponse<?> list(HttpServletRequest r) { return ok(applications.list(owner(r)),r); }
    @PostMapping public ApiResponse<?> create(@Valid @RequestBody CreateRequest body,HttpServletRequest r) {
        return ok(applications.create(owner(r),body.jobId()),r);
    }
    @PatchMapping("/{id}") public ApiResponse<?> update(@RequestBody UpdateRequest body,@PathVariable String id,HttpServletRequest r) {
        return ok(applications.update(owner(r),id,body.status(),body.note()),r);
    }
}
