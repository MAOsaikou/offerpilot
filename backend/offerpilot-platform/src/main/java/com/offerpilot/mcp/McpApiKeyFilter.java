package com.offerpilot.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** The sole authentication gate for MCP; construct in the security chain, not as a servlet bean. */
public final class McpApiKeyFilter extends OncePerRequestFilter {
    private final byte[] expectedKey;
    private final ObjectMapper json;
    private final List<String> paths;

    public McpApiKeyFilter(String apiKey, ObjectMapper json, Environment environment) {
        this.expectedKey = apiKey == null || apiKey.isBlank() ? new byte[0] : apiKey.getBytes(StandardCharsets.UTF_8);
        this.json = json;
        String base = environment.getProperty("spring.ai.mcp.server.base-url", "");
        String sse = environment.getProperty("spring.ai.mcp.server.sse-endpoint", "/sse");
        String message = environment.getProperty("spring.ai.mcp.server.sse-message-endpoint", "/mcp/message");
        // In SDK 0.10 base-url only prefixes the advertised message URL; router paths remain unprefixed.
        this.paths = List.of("/mcp", "/sse", sse, message, base + sse, base + message);
    }

    public boolean isMcpRequest(HttpServletRequest request) {
        String path = request.getServletPath();
        return paths.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isMcpRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-API-Key");
        if (expectedKey.length == 0 || supplied == null
                || !MessageDigest.isEqual(expectedKey, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control", "no-store");
            json.writeValue(response.getOutputStream(), ApiResponse.fail(ErrorCode.UNAUTHENTICATED.code(),
                    "MCP requires a valid X-API-Key", TraceIdFilter.current(request)));
            return;
        }
        chain.doFilter(request, response);
    }
}
