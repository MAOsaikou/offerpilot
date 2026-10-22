package com.offerpilot.career.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.career.application.CareerRecommendationService;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;

@RestController
@RequestMapping("/api/v1/career")
public class CareerRecommendationController {
    private final CareerRecommendationService service;
    private final CurrentUser users;
    public CareerRecommendationController(CareerRecommendationService service, CurrentUser users) {
        this.service = service; this.users = users;
    }
    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) { return ApiResponse.ok(data, TraceIdFilter.current(request)); }
    private String owner(HttpServletRequest request) { return users.require(request.getHeader("Authorization")); }
    @GetMapping("/occupations") public ApiResponse<?> catalog(HttpServletRequest request) {
        owner(request); return ok(service.catalog(), request);
    }
    @GetMapping("/compare") public ApiResponse<?> compare(@RequestParam List<String> ids, HttpServletRequest request) {
        return ok(service.compare(owner(request), ids), request);
    }
    @GetMapping("/recommendations") public ApiResponse<?> latest(HttpServletRequest request) {
        var revision = service.latest(owner(request));
        return ok(revision == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : revision, request);
    }
    @GetMapping("/recommendations/{id}") public ApiResponse<?> revision(@PathVariable String id, HttpServletRequest request) {
        return ok(service.revision(owner(request), id), request);
    }
    @PostMapping("/recommendations") public ApiResponse<?> create(HttpServletRequest request) {
        return ok(service.create(owner(request)), request);
    }
    public record RerankRequest(JsonNode city, JsonNode minSalaryK, JsonNode avoidOvertime, JsonNode research) {
        CareerRecommendationService.Constraints constraints() {
            if (city == null || !city.isTextual() || avoidOvertime == null || !avoidOvertime.isBoolean()
                    || research == null || !research.isBoolean()
                    || minSalaryK != null && !minSalaryK.isNull() && (!minSalaryK.isIntegralNumber() || !minSalaryK.canConvertToInt()))
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请提供城市文本、整数月薪下限及布尔偏好");
            return new CareerRecommendationService.Constraints(city.asText(),
                    minSalaryK == null || minSalaryK.isNull() ? null : minSalaryK.asInt(),
                    avoidOvertime.asBoolean(), research.asBoolean());
        }
    }
    @PostMapping("/recommendations/{id}/rerank") public ApiResponse<?> rerank(@PathVariable String id,
            @RequestBody RerankRequest body, HttpServletRequest request) {
        return ok(service.rerank(owner(request), id, body.constraints()), request);
    }
}
