package com.offerpilot.career.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.career.application.CareerQuestionnaire;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/career/questionnaire")
public class CareerQuestionnaireController {
    public record Answer(@NotNull Integer value) {}
    public record Complete(@NotNull Integer revision) {}
    private final CurrentUser users;
    private final CareerQuestionnaire service;
    public CareerQuestionnaireController(CurrentUser users,CareerQuestionnaire service) { this.users=users; this.service=service; }
    @GetMapping public ApiResponse<?> get(HttpServletRequest request) {
        return ApiResponse.ok(service.get(owner(request)),TraceIdFilter.current(request));
    }
    @PutMapping("/answers/{id}") public ApiResponse<?> answer(@PathVariable String id,@Valid @RequestBody Answer body,HttpServletRequest request) {
        return ApiResponse.ok(service.answer(owner(request),id,body.value()),TraceIdFilter.current(request));
    }
    @PostMapping("/complete") public ApiResponse<?> complete(@Valid @RequestBody Complete body,HttpServletRequest request) {
        return ApiResponse.ok(service.complete(owner(request),body.revision()),TraceIdFilter.current(request));
    }
    private String owner(HttpServletRequest request) { return users.require(request.getHeader("Authorization")); }
}
