package com.offerpilot.auth.web;

import com.offerpilot.auth.application.StaffUserAdminService;
import com.offerpilot.auth.application.StaffUserAdminService.UserRow;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客服端账号管理接口。所有端点都要求 Bearer JWT 且账号包含 STAFF 角色。
 */
@RestController
@RequestMapping("/api/v1/staff/users")
public class StaffUserController {
    private final StaffUserAdminService admin;

    public StaffUserController(StaffUserAdminService admin) { this.admin = admin; }

    public record StatusRequest(@NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status) {}

    /** 列出所有用户端账号（不含客服账号），按创建时间倒序。 */
    @GetMapping
    public ApiResponse<List<UserRow>> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(admin.listUsers(authorization), TraceIdFilter.current(request));
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
