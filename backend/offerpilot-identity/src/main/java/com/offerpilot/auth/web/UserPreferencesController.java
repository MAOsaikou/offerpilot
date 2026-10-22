package com.offerpilot.auth.web;

import com.offerpilot.auth.application.UserPreferences;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/preferences")
public class UserPreferencesController {
    private final CurrentUser users;
    private final UserPreferences preferences;

    public UserPreferencesController(CurrentUser users, UserPreferences preferences) {
        this.users = users;
        this.preferences = preferences;
    }

    @GetMapping public ApiResponse<UserPreferences.View> get(HttpServletRequest request) {
        return ApiResponse.ok(preferences.get(users.require(request.getHeader("Authorization"))), TraceIdFilter.current(request));
    }

    @PutMapping public ApiResponse<UserPreferences.View> save(@Valid @RequestBody UserPreferences.Selection selection,
                                                            HttpServletRequest request) {
        return ApiResponse.ok(preferences.save(users.require(request.getHeader("Authorization")), selection),
                TraceIdFilter.current(request));
    }
}
