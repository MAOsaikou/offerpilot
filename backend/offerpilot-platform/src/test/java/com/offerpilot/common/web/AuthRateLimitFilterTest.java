package com.offerpilot.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.ratelimit.AuthRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimitFilterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final OfferPilotProperties.RateLimit limits = new OfferPilotProperties.RateLimit(60, 2, 1, 2, 3, 4);

    @Test
    void onlyExactAuthPostsAreLimitedAndRawHeadersAreNotRead() throws Exception {
        var calls = new ArrayList<String>();
        var limiter = stub((ip, bucket, limit) -> {
            calls.add(ip + "|" + bucket + "|" + limit);
            return new AuthRateLimiter.Result(true, 0, 60);
        });
        var filter = new AuthRateLimitFilter(limiter, limits, json);
        var paths = List.of("guest", "login", "register", "staff/login");
        var buckets = List.of("guest", "login", "register", "staff-login");
        for (int i = 0; i < paths.size(); i++) {
            var request = request("POST", "/api/v1/auth/" + paths.get(i));
            request.addHeader("X-Forwarded-For", "untrusted-input");
            var passed = new AtomicBoolean();
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set(true));
            assertThat(passed).isTrue();
            assertThat(calls.get(i)).isEqualTo("192.0.2.10|" + buckets.get(i) + "|" + (i + 1));
        }
        int afterLimited = calls.size();
        for (String path : List.of("/api/v1/auth/verify", "/api/v1/auth/refresh", "/api/v1/auth/demo-login", "/api/v1/agent/chat")) {
            filter.doFilter(request("POST", path), new MockHttpServletResponse(), (req, res) -> {});
        }
        for (String method : List.of("GET", "OPTIONS")) {
            filter.doFilter(request(method, "/api/v1/auth/guest"), new MockHttpServletResponse(), (req, res) -> {});
        }
        assertThat(calls).hasSize(afterLimited);
    }

    @Test
    void rejectionStopsChainAndUsesExistingErrorEnvelopeWithTraceAndRetryHeaders() throws Exception {
        var limiter = stub((ip, bucket, limit) -> new AuthRateLimiter.Result(false, 0, 7));
        var response = new MockHttpServletResponse();
        var passed = new AtomicBoolean();
        new AuthRateLimitFilter(limiter, limits, json).doFilter(request("POST", "/api/v1/auth/guest"), response,
                (req, res) -> passed.set(true));
        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("7");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("1");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var body = json.readTree(response.getContentAsString());
        assertThat(body.path("code").asInt()).isEqualTo(ErrorCode.RATE_LIMITED.code());
        assertThat(body.path("message").asText()).isEqualTo(ErrorCode.RATE_LIMITED.message());
        assertThat(body.path("traceId").asText()).isEqualTo("test-trace");
    }

    @Test
    void redisOutageFailsClosedWithConsistent503() throws Exception {
        var limiter = stub((ip, bucket, limit) -> {
            throw new RedisConnectionFailureException("isolated outage");
        });
        var response = new MockHttpServletResponse();
        var passed = new AtomicBoolean();
        new AuthRateLimitFilter(limiter, limits, json).doFilter(request("POST", "/api/v1/auth/login"), response,
                (req, res) -> passed.set(true));
        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(json.readTree(response.getContentAsString()).path("code").asInt()).isEqualTo(ErrorCode.CACHE_UNAVAILABLE.code());
        assertThat(response.getContentAsString()).doesNotContain("isolated outage");
    }

    private AuthRateLimiter stub(TriFunction<String, String, Integer, AuthRateLimiter.Result> behavior) {
        return new AuthRateLimiter(null, Clock.systemUTC()) {
            @Override
            public Result consume(String clientIp, String bucket, int limit) {
                return behavior.apply(clientIp, bucket, limit);
            }
        };
    }

    @FunctionalInterface
    private interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr("192.0.2.10");
        request.setAttribute(TraceIdFilter.ATTR, "test-trace");
        return request;
    }
}