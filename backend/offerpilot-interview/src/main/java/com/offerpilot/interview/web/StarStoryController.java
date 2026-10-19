package com.offerpilot.interview.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.interview.application.StarStoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/star-stories")
public class StarStoryController {
    public record SaveRequest(@NotBlank String sessionId, @NotNull @Min(0) @Max(14) Integer questionIndex) {}

    private final StarStoryService stories;
    private final CurrentUser users;

    public StarStoryController(StarStoryService stories, CurrentUser users) {
        this.stories = stories;
        this.users = users;
    }

    private String owner(HttpServletRequest request) {
        return users.require(request.getHeader("Authorization"));
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, TraceIdFilter.current(request));
    }

    @GetMapping
    public ApiResponse<?> list(HttpServletRequest request) {
        return ok(stories.list(owner(request)), request);
    }

    @GetMapping("/candidates")
    public ApiResponse<?> candidates(@RequestParam String sessionId, HttpServletRequest request) {
        return ok(stories.candidates(owner(request), sessionId), request);
    }

    @PostMapping
    public ApiResponse<?> save(@Valid @RequestBody SaveRequest body, HttpServletRequest request) {
        return ok(stories.saveFromSession(owner(request), body.sessionId(), body.questionIndex()), request);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable String id, HttpServletRequest request) {
        stories.delete(owner(request), id);
        return ok(null, request);
    }

    @DeleteMapping
    public ApiResponse<?> clear(HttpServletRequest request) {
        stories.clear(owner(request));
        return ok(null, request);
    }
}
