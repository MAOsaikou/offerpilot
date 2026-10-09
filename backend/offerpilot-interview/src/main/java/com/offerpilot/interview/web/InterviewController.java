package com.offerpilot.interview.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.interview.application.InterviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/interviews")
public class InterviewController {
    private final InterviewService interviews;
    private final CurrentUser users;

    public InterviewController(InterviewService interviews, CurrentUser users) {
        this.interviews = interviews;
        this.users = users;
    }

    private String owner(HttpServletRequest request) { return users.require(request.getHeader("Authorization")); }
    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, TraceIdFilter.current(request));
    }

    // Regex bounds count Unicode code points, matching PostgreSQL char_length.
    public record CreateRequest(
            @NotBlank @Pattern(regexp = "(?s).{1,100}") String role,
            @NotNull @Pattern(regexp = "项目与经历|表达与结构|职业动机") String kind) {}
    public record ProgressRequest(@Min(0) @Max(4) Integer questionIndex, Boolean completed) {}
    public record AnswerRequest(@NotNull @Pattern(regexp = "(?s).{0,12000}") String answerText) {}

    @GetMapping
    public ApiResponse<?> list(HttpServletRequest request) { return ok(interviews.list(owner(request)), request); }

    @PostMapping
    public ApiResponse<?> create(@Valid @RequestBody CreateRequest body, HttpServletRequest request) {
        return ok(interviews.create(owner(request), body.role(), body.kind()), request);
    }

    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable String id, HttpServletRequest request) {
        return ok(interviews.get(owner(request), id), request);
    }

    @PatchMapping("/{id}")
    public ApiResponse<?> update(@PathVariable String id, @Valid @RequestBody ProgressRequest body,
                                 HttpServletRequest request) {
        return ok(interviews.update(owner(request), id, body.questionIndex(), body.completed()), request);
    }

    @PutMapping("/{id}/answers/{index}")
    public ApiResponse<?> saveAnswer(@PathVariable String id, @PathVariable String index,
                                     @Valid @RequestBody AnswerRequest body, HttpServletRequest request) {
        return ok(interviews.saveAnswer(owner(request), id, parseIndex(index), body.answerText()), request);
    }

    private int parseIndex(String index) {
        try {
            return Integer.parseInt(index);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

}
