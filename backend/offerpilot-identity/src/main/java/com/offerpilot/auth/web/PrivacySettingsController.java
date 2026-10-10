package com.offerpilot.auth.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.auth.application.PrivacySettings;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/privacy-settings")
public class PrivacySettingsController {
    public record Text(@NotNull @Size(max=64000) String text) {}
    private final CurrentUser users;
    private final PrivacySettings settings;
    public PrivacySettingsController(CurrentUser users, PrivacySettings settings) { this.users = users; this.settings = settings; }
    @GetMapping public ApiResponse<?> get(HttpServletRequest request) {
        return ApiResponse.ok(settings.get(users.require(request.getHeader("Authorization"))), TraceIdFilter.current(request));
    }
    @PutMapping public ApiResponse<?> save(@Valid @RequestBody PrivacySettings.Selection selection, HttpServletRequest request) {
        return ApiResponse.ok(settings.save(users.require(request.getHeader("Authorization")), selection), TraceIdFilter.current(request));
    }
    @PostMapping("/check") public ApiResponse<?> check(@Valid @RequestBody Text text, HttpServletRequest request) {
        return ApiResponse.ok(settings.check(users.require(request.getHeader("Authorization")), text.text()), TraceIdFilter.current(request));
    }
}
