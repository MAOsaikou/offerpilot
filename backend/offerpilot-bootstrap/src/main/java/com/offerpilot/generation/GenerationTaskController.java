package com.offerpilot.generation;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/generation-tasks")
public class GenerationTaskController {
    private final GenerationTaskService tasks;
    private final CurrentUser users;

    public GenerationTaskController(GenerationTaskService tasks, CurrentUser users) {
        this.tasks = tasks;
        this.users = users;
    }

    @GetMapping
    public ApiResponse<?> list(HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        return ApiResponse.ok(tasks.listRunning(owner), TraceIdFilter.current(request));
    }
}
