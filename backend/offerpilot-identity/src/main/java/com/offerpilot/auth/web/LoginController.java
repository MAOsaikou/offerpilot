package com.offerpilot.auth.web;

import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.auth.application.RefreshTokenService;
import com.offerpilot.common.error.BusinessException;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
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
    /** HttpOnly, SameSite=Strict, scoped to /api/v1/auth so no other request ever carries it. */
    static final String REFRESH_COOKIE = "op_refresh";
    private static final String COOKIE_PATH = "/api/v1/auth";
    private final PasswordLoginService logins;
    private final RefreshTokenService refreshTokens;
    private final boolean secureCookie;

    public LoginController(PasswordLoginService logins, RefreshTokenService refreshTokens,
                           @Value("${offerpilot.security.refresh-cookie-secure:true}") boolean secureCookie) {
        this.logins = logins;
        this.refreshTokens = refreshTokens;
        this.secureCookie = secureCookie;
    }

    private PasswordLoginService.LoginResponse withRefresh(PasswordLoginService.LoginResponse login,
                                                           HttpServletRequest request, HttpServletResponse response) {
        setCookie(response, refreshTokens.issue(login.user().id(), request.getHeader(HttpHeaders.USER_AGENT)));
        return login;
    }

    private void setCookie(HttpServletResponse response, RefreshTokenService.Issued issued) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH_COOKIE, issued.token()).httpOnly(true)
                .secure(secureCookie).sameSite("Strict").path(COOKIE_PATH).maxAge(issued.ttl()).build().toString());
    }

    private void clearCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH_COOKIE, "").httpOnly(true)
                .secure(secureCookie).sameSite("Strict").path(COOKIE_PATH).maxAge(0).build().toString());
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) if (REFRESH_COOKIE.equals(c.getName())) return c.getValue();
        return null;
    }

    /** AC-01: exchange the refresh cookie for a new access token, rotating the cookie. */
    @PostMapping("/refresh")
    public ApiResponse<PasswordLoginService.LoginResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        try {
            var rotation = refreshTokens.rotate(cookie(request), request.getHeader(HttpHeaders.USER_AGENT));
            var login = logins.refreshed(rotation.userId());
            if (rotation.next() != null) setCookie(response, rotation.next());
            return ApiResponse.ok(login, TraceIdFilter.current(request));
        } catch (BusinessException ex) {
            clearCookie(response);
            throw ex;
        }
    }

    /** Ends this browser's refresh session; the short-lived access token simply expires. */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        refreshTokens.revoke(cookie(request));
        clearCookie(response);
        return ApiResponse.ok(null, TraceIdFilter.current(request));
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    /** 用户端登录。 */
    @PostMapping("/login")
    public ApiResponse<PasswordLoginService.LoginResponse> login(@Valid @RequestBody LoginRequest body,
                                                                 HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(withRefresh(logins.login(body.email(), body.password()), request, response), TraceIdFilter.current(request));
    }

    /** 用户端注册：仅需邮箱 + 密码，成功后自动签发令牌（即注册即登录）。 */
    @PostMapping("/register")
    public ApiResponse<PasswordLoginService.LoginResponse> register(@Valid @RequestBody LoginRequest body,
                                                                    HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(withRefresh(logins.register(body.email(), body.password()), request, response), TraceIdFilter.current(request));
    }

    /** 客服端登录（要求 STAFF 角色）。 */
    @PostMapping("/staff/login")
    public ApiResponse<PasswordLoginService.LoginResponse> staffLogin(@Valid @RequestBody LoginRequest body,
                                                                      HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(withRefresh(logins.staffLogin(body.email(), body.password()), request, response), TraceIdFilter.current(request));
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
