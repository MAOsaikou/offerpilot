package com.offerpilot.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.ratelimit.AuthRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Runs after CORS, before controllers/body parsing, only for the four password/guest POSTs. */
public class AuthRateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);
    private final AuthRateLimiter limiter;
    private final OfferPilotProperties.RateLimit limits;
    private final ObjectMapper json;

    public AuthRateLimitFilter(AuthRateLimiter limiter, OfferPilotProperties.RateLimit limits, ObjectMapper json) {
        this.limiter = limiter;
        this.limits = limits;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || bucket(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String bucket = bucket(request);
        int limit = switch (bucket) {
            case "guest" -> limits.authGuestPerMinute();
            case "login" -> limits.authLoginPerMinute();
            case "register" -> limits.authRegisterPerMinute();
            case "staff-login" -> limits.authStaffLoginPerMinute();
            default -> throw new IllegalStateException("Unknown auth rate-limit bucket");
        };
        AuthRateLimiter.Result result;
        try {
            // Spring resolves forwarded headers before the security chain. Only deploy behind
            // a trusted proxy that strips/overwrites client-supplied forwarded headers.
            result = limiter.consume(request.getRemoteAddr(), bucket, limit);
        } catch (DataAccessException | IllegalStateException ex) {
            log.warn("Auth rate-limit store unavailable traceId={}", TraceIdFilter.current(request), ex);
            writeError(request, response, ErrorCode.CACHE_UNAVAILABLE);
            return;
        }
        response.setHeader("X-RateLimit-Limit", Integer.toString(limit));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(result.remaining()));
        if (!result.allowed()) {
            response.setHeader("Retry-After", Long.toString(result.retryAfterSeconds()));
            writeError(request, response, ErrorCode.RATE_LIMITED);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String bucket(HttpServletRequest request) {
        return switch (request.getServletPath()) {
            case "/api/v1/auth/guest" -> "guest";
            case "/api/v1/auth/login" -> "login";
            case "/api/v1/auth/register" -> "register";
            case "/api/v1/auth/staff/login" -> "staff-login";
            default -> null;
        };
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, ErrorCode error) throws IOException {
        response.setStatus(error.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), ApiResponse.fail(error.code(), error.message(), TraceIdFilter.current(request)));
    }
}
