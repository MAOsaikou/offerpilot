package com.offerpilot.resume.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.resume.application.ResumeModelSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/resume-model-settings")
public class ResumeModelSettingsController {
    private final ResumeModelSettings settings;
    private final CurrentUser users;
    public ResumeModelSettingsController(ResumeModelSettings settings, CurrentUser users) { this.settings = settings; this.users = users; }
    @GetMapping
    public ApiResponse<?> get(HttpServletRequest request) {
        return ApiResponse.ok(settings.get(users.require(request.getHeader("Authorization"))), TraceIdFilter.current(request));
    }
    @PutMapping
    public ApiResponse<?> save(@RequestBody ResumeModelSettings.Selection selection, HttpServletRequest request) {
        return ApiResponse.ok(settings.save(users.require(request.getHeader("Authorization")), selection), TraceIdFilter.current(request));
    }
}
