package com.offerpilot.auth.web;

import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GuestController {
    private final GuestIdentityService identities;
    public GuestController(GuestIdentityService identities) { this.identities = identities; }

    @PostMapping("/api/v1/auth/guest")
    public ApiResponse<GuestIdentityService.Credential> create(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(identities.create(), TraceIdFilter.current(request));
    }
}
