package com.offerpilot.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class McpApiKeyFilterTest {
    @Test
    void emptyOrBlankConfiguredKeyFailsClosedEvenWithAHeader() throws Exception {
        for (String configured : new String[]{"", "  "}) {
            var filter = new McpApiKeyFilter(configured, new ObjectMapper(), new MockEnvironment());
            var request = request("/sse", "anything");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> fail("Empty key must never admit a request"));
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("20001", "X-API-Key").doesNotContain("anything");
        }
    }

    @Test
    void customTransportPathsAreProtectedAndApiPathsRemainOutsideThisGate() throws Exception {
        var environment = new MockEnvironment().withProperty("spring.ai.mcp.server.base-url", "/transport")
                .withProperty("spring.ai.mcp.server.sse-endpoint", "/events")
                .withProperty("spring.ai.mcp.server.sse-message-endpoint", "/messages");
        var filter = new McpApiKeyFilter("unit-test-only", new ObjectMapper(), environment);
        for (String path : new String[]{"/mcp", "/mcp/message", "/sse", "/events", "/messages", "/transport/events", "/transport/messages"}) {
            assertThat(filter.isMcpRequest(request(path, null))).isTrue();
            var response = new MockHttpServletResponse();
            filter.doFilter(request(path, null), response, (req, res) -> fail("Key is required"));
            assertThat(response.getStatus()).isEqualTo(401);
            var admitted = new AtomicBoolean();
            filter.doFilter(request(path, "unit-test-only"), new MockHttpServletResponse(), (req, res) -> admitted.set(true));
            assertThat(admitted).isTrue();
        }
        for (String path : new String[]{"/api/v1/jobs/search", "/api/v1/auth/login", "/sse-other"})
            assertThat(filter.isMcpRequest(request(path, null))).isFalse();
    }

    private MockHttpServletRequest request(String path, String key) {
        var request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        if (key != null) request.addHeader("X-API-Key", key);
        return request;
    }
}
