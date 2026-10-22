package com.offerpilot.career.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.career.application.*;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/career")
public class CareerUpdatesController {
    @ModelAttribute
    void preventCaching(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    private final CurrentUser users; private final CareerTargetPlans plans; private final ProfileSuggestions suggestions;
    public CareerUpdatesController(CurrentUser users,CareerTargetPlans plans,ProfileSuggestions suggestions) { this.users=users;this.plans=plans;this.suggestions=suggestions; }
    private String owner(HttpServletRequest r) { return users.require(r.getHeader("Authorization")); }
    public record Revision(@NotBlank @Size(max=100) String revision) {}
    public record Decision(@NotNull Boolean accept,@NotNull @Size(max=50) List<@NotBlank @Size(max=100) String> skills) {}
    @GetMapping("/target-plan")
    public ApiResponse<CareerTargetPlans.Plan> plan(HttpServletRequest r) { return ApiResponse.ok(plans.get(owner(r)),TraceIdFilter.current(r)); }
    @PostMapping("/target-plan/confirm")
    public ApiResponse<CareerTargetPlans.Plan> confirm(@Valid @RequestBody Revision body,HttpServletRequest r) { return ApiResponse.ok(plans.confirm(owner(r),body.revision()),TraceIdFilter.current(r)); }
    @PostMapping("/target-plan/retry")
    public ApiResponse<CareerTargetPlans.Plan> retry(@Valid @RequestBody Revision body,HttpServletRequest r) { return ApiResponse.ok(plans.retry(owner(r),body.revision()),TraceIdFilter.current(r)); }
    @GetMapping("/profile-suggestions")
    public ApiResponse<List<ProfileSuggestions.Suggestion>> suggestions(HttpServletRequest r) { return ApiResponse.ok(suggestions.list(owner(r)),TraceIdFilter.current(r)); }
    @PostMapping("/profile-suggestions/{id}/decision")
    public ApiResponse<CareerProfileService.Profile> decide(@PathVariable String id,@Valid @RequestBody Decision body,HttpServletRequest r) {
        return ApiResponse.ok(suggestions.decide(owner(r),id,body.accept(),body.skills()),TraceIdFilter.current(r));
    }
}
