package com.offerpilot.interview.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.interview.application.InterviewRecapExporter;
import com.offerpilot.interview.application.InterviewService;
import com.offerpilot.interview.application.InterviewService.MetricSample;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/interviews")
public class InterviewController {
    private final InterviewService interviews;
    private final InterviewRecapExporter recaps;
    private final CurrentUser users;

    public InterviewController(InterviewService interviews, InterviewRecapExporter recaps, CurrentUser users) {
        this.interviews = interviews;
        this.recaps = recaps;
        this.users = users;
    }

    private String owner(HttpServletRequest request) {
        return users.require(request.getHeader("Authorization"));
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, TraceIdFilter.current(request));
    }

    // Regex bounds count Unicode code points, matching PostgreSQL char_length.
    public record CreateRequest(
            @NotBlank @Pattern(regexp = "(?s).{1,100}") String role,
            @NotNull @Pattern(regexp = "项目与经历|表达与结构|职业动机|技术|行为|HR|综合", message = "请选择有效的面试类型") String kind, @Pattern(regexp = "\\S{1,80}") String jobId,
            String resumeId, String versionId, String difficulty, String mode, Integer durationMinutes, @Min(3) @Max(15) Integer questionCount, java.util.Map<String,Integer> typeMix, String industry, String feedbackMode) {}
    public record ProgressRequest(@Min(0) @Max(14) Integer questionIndex, Boolean completed) {}
    public record AnswerRequest(@NotNull(message = "请提供 answerText，可为空字符串") @Pattern(regexp = "(?s).{0,12000}", message = "回答不能超过12000个字符") String answerText) {}

    @GetMapping
    public ApiResponse<?> list(HttpServletRequest request) {
        return ok(interviews.list(owner(request)), request);
    }

    @PostMapping
    public ApiResponse<?> create(@Valid @RequestBody CreateRequest body, HttpServletRequest request) {
        String owner = owner(request);
        if (body.difficulty() == null && body.mode() == null && body.durationMinutes() == null && body.resumeId() == null && body.versionId() == null && body.questionCount()==null && body.typeMix()==null && body.industry()==null && body.feedbackMode()==null)
            return ok(interviews.create(owner, body.role(), body.kind(), body.jobId()), request);
        var options = new com.offerpilot.interview.application.InterviewOptions(body.resumeId(), body.versionId(),
                body.difficulty() == null ? "STANDARD" : body.difficulty(), body.mode() == null ? "TEXT" : body.mode(),
                body.durationMinutes() == null ? 30 : body.durationMinutes(), body.questionCount()==null ? 0 : body.questionCount(), body.typeMix(), body.industry(), body.feedbackMode());
        return ok(interviews.create(owner, body.role(), body.kind(), body.jobId(), options, body.questionCount()!=null || body.typeMix()!=null || body.industry()!=null || body.feedbackMode()!=null), request);
    }

    public record MetricsRequest(@NotEmpty @Size(max = 240)
            java.util.List<@NotNull @Valid MetricSample> samples) {}

    @PostMapping("/{id}/metrics")
    public ApiResponse<?> metrics(@PathVariable String id, @Valid @RequestBody MetricsRequest body,
                                  HttpServletRequest request) {
        return ok(interviews.postMetrics(owner(request), id, body.samples()), request);
    }

    @GetMapping("/{id}/report")
    public ApiResponse<?> report(@PathVariable String id, HttpServletRequest request) {
        return ok(interviews.report(owner(request), id), request);
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

    @PostMapping("/{id}/answers/{index}/follow-up")
    public ApiResponse<?> followUp(@PathVariable String id, @PathVariable String index, HttpServletRequest request) {
        return ok(interviews.followUp(owner(request), id, parseIndex(index)), request);
    }

    @PutMapping("/{id}/answers/{index}/follow-up")
    public ApiResponse<?> saveFollowUpAnswer(@PathVariable String id, @PathVariable String index,
                                             @Valid @RequestBody AnswerRequest body, HttpServletRequest request) {
        return ok(interviews.saveFollowUpAnswer(owner(request), id, parseIndex(index), body.answerText()), request);
    }

    @GetMapping(value = "/{id}/recap.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<byte[]> recapMarkdown(@PathVariable String id, HttpServletRequest request) {
        String markdown = recaps.markdown(owner(request), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment()
                        .filename("面试复盘.md", java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "markdown", java.nio.charset.StandardCharsets.UTF_8))
                .body(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private int parseIndex(String index) {
        try {
            int value = Integer.parseInt(index);
            if (value < 0 || value > 14) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            return value;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

}
