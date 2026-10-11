package com.offerpilot.demo;

import com.offerpilot.auth.application.DemoAccountBootstrap;
import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {
    private final DemoAccountBootstrap accounts;
    private final DemoResetService resets;
    private final PasswordLoginService logins;
    private final DemoSandboxService sandboxes;
    private final com.offerpilot.auth.application.CurrentUser users;

    public DemoController(DemoAccountBootstrap accounts, DemoResetService resets, PasswordLoginService logins, DemoSandboxService sandboxes, com.offerpilot.auth.application.CurrentUser users) {
        this.sandboxes = sandboxes;
        this.users = users;
        this.accounts = accounts;
        this.resets = resets;
        this.logins = logins;
    }

    @PostMapping("/api/v1/auth/demo-login")
    public ApiResponse<PasswordLoginService.LoginResponse> demoLogin(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var login = sandboxes.enabled() ? sandboxes.login() : accounts.login(logins);
        if (!sandboxes.enabled()) resets.seedIfEmpty(login.user().id());
        return ApiResponse.ok(login, TraceIdFilter.current(request));
    }

    @org.springframework.web.bind.annotation.GetMapping("/api/v1/demo/sandbox")
    public ApiResponse<DemoSandboxService.Status> sandboxStatus(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(sandboxes.status(users.require(authorization)), TraceIdFilter.current(request));
    }

    @PostMapping("/api/v1/admin/demo/reset")
    public ApiResponse<DemoResetService.Summary> reset(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        logins.requireStaffId(authorization);
        return ApiResponse.ok(resets.reset(), TraceIdFilter.current(request));
    }
}
