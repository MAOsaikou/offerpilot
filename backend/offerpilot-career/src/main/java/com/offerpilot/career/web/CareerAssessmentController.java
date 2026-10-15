package com.offerpilot.career.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.career.application.CareerAssessmentInference;
import com.offerpilot.career.application.CareerAssessmentService;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** CD-01/CD-02 conversational assessment: answer one question per turn, infer, then confirm or correct. */
@RestController
@RequestMapping("/api/v1/career/assessment")
public class CareerAssessmentController {
    private final CareerAssessmentService assessments;
    private final CurrentUser users;

    public CareerAssessmentController(CareerAssessmentService assessments, CurrentUser users) {
        this.assessments = assessments;
        this.users = users;
    }

    public record AnswerRequest(@NotNull @Pattern(regexp = "(?s).{0,1000}", message = "回答最多 1000 字") String answer) {}
    public record ConfirmRequest(@NotNull Map<String, Integer> riasec,
                                 @NotNull @Size(max = 8, message = "最多保留 8 个特质") List<@NotNull @Pattern(regexp = "(?s).{1,12}") String> traits,
                                 @Pattern(regexp = "(?s).{0,50}") String city,
                                 @Pattern(regexp = "(?s).{0,50}") String salary,
                                 @Pattern(regexp = "(?s).{0,50}") String overtime) {}

    private String owner(HttpServletRequest request) { return users.require(request.getHeader("Authorization")); }
    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) { return ApiResponse.ok(data, TraceIdFilter.current(request)); }

    @GetMapping
    public ApiResponse<CareerAssessmentService.Assessment> get(HttpServletRequest request) {
        return ok(assessments.get(owner(request)), request);
    }

    @PutMapping("/answers/{step}")
    public ApiResponse<CareerAssessmentService.Assessment> answer(@PathVariable String step, @Valid @RequestBody AnswerRequest body,
                                                                  HttpServletRequest request) {
        int index;
        try { index = Integer.parseInt(step); } catch (NumberFormatException ex) { throw new BusinessException(ErrorCode.VALIDATION_FAILED); }
        return ok(assessments.saveAnswer(owner(request), index, body.answer()), request);
    }

    @PostMapping("/infer")
    public ApiResponse<CareerAssessmentService.Assessment> infer(HttpServletRequest request) {
        return ok(assessments.infer(owner(request)), request);
    }

    @PutMapping("/confirm")
    public ApiResponse<CareerAssessmentService.Assessment> confirm(@Valid @RequestBody ConfirmRequest body, HttpServletRequest request) {
        var constraints = new CareerAssessmentInference.Constraints(strip(body.city()), strip(body.salary()), strip(body.overtime()));
        return ok(assessments.confirm(owner(request), body.riasec(), body.traits(), constraints), request);
    }

    @DeleteMapping
    public ApiResponse<CareerAssessmentService.Assessment> reset(HttpServletRequest request) {
        String owner = owner(request);
        assessments.reset(owner);
        return ok(assessments.get(owner), request);
    }

    private static String strip(String value) { return value == null ? "" : value.strip(); }
}
