package com.offerpilot.auth.web;

import com.offerpilot.auth.application.StaffUserAdminService;
import com.offerpilot.auth.application.StaffUserAdminService.UserPage;
import com.offerpilot.auth.application.StaffUserAdminService.UserRow;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 客服端账号管理接口。所有端点都要求 Bearer JWT 且账号包含 STAFF 角色。
 */
@RestController
@RequestMapping("/api/v1/staff/users")
public class StaffUserController {
    private final StaffUserAdminService admin;

    public StaffUserController(StaffUserAdminService admin) { this.admin = admin; }

    public record StatusRequest(@NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status) {}

    /** 分页列出用户端账号（不含客服账号），按创建时间倒序。 */
    @GetMapping
    public ApiResponse<UserPage> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean hideGuests,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(admin.listUsers(authorization, page, size, hideGuests), TraceIdFilter.current(request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> invalidQuery(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED.code(),
                "page/size 必须为整数，hideGuests 必须为布尔值", TraceIdFilter.current(request)));
    }

    /** 启用/禁用用户端账号。 */
    @PatchMapping("/{id}/status")
    public ApiResponse<UserRow> setStatus(@PathVariable String id,
            @Valid @RequestBody StatusRequest body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(admin.setUserStatus(authorization, id, body.status()),
                TraceIdFilter.current(request));
    }
}
