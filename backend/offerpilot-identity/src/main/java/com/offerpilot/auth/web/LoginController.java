package com.offerpilot.auth.web;

import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class LoginController {
    private final PasswordLoginService logins;

    public LoginController(PasswordLoginService logins) { this.logins = logins; }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    /** 用户端登录。 */
    @PostMapping("/login")
    public ApiResponse<PasswordLoginService.LoginResponse> login(@Valid @RequestBody LoginRequest body,
                                                                 HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(logins.login(body.email(), body.password()), TraceIdFilter.current(request));
    }

    /** 用户端注册：仅需邮箱 + 密码，成功后自动签发令牌（即注册即登录）。 */
    @PostMapping("/register")
    public ApiResponse<PasswordLoginService.LoginResponse> register(@Valid @RequestBody LoginRequest body,
                                                                    HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(logins.register(body.email(), body.password()), TraceIdFilter.current(request));
    }

    /** 客服端登录（要求 STAFF 角色）。 */
    @PostMapping("/staff/login")
    public ApiResponse<PasswordLoginService.LoginResponse> staffLogin(@Valid @RequestBody LoginRequest body,
                                                                      HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(logins.staffLogin(body.email(), body.password()), TraceIdFilter.current(request));
    }

    /** 令牌解析与比对校验：携带 Bearer 令牌验证签名、有效期与账号状态。 */
    @GetMapping("/verify")
    public ApiResponse<PasswordLoginService.VerifyResponse> verify(
            @org.springframework.web.bind.annotation.RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(logins.verify(authorization), TraceIdFilter.current(request));
    }
}
