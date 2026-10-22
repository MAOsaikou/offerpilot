package com.offerpilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/mcp/servers")
public class McpConnectionController {
    private final CurrentUser users;
    private final McpConnections connections;
    public McpConnectionController(CurrentUser users, McpConnections connections) { this.users = users; this.connections = connections; }
    @ModelAttribute
    void preventCaching(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    public record Add(@NotBlank @Pattern(regexp="jobs|search|github") String provider) {}
    public record Toggle(@NotNull Boolean enabled) {}
    public record Call(@NotBlank @Size(max=128) String tool, @NotNull JsonNode arguments) {}
    @GetMapping
    public ApiResponse<List<McpConnections.Connection>> list(@RequestHeader(value="Authorization",required=false) String auth, HttpServletRequest request) {
        return ApiResponse.ok(connections.list(users.require(auth)), TraceIdFilter.current(request));
    }
    @PostMapping
    public ApiResponse<McpConnections.Connection> add(@RequestHeader(value="Authorization",required=false) String auth, @Valid @RequestBody Add body, HttpServletRequest request) {
        return ApiResponse.ok(connections.add(users.require(auth), body.provider()), TraceIdFilter.current(request));
    }
    @PatchMapping("/{provider}")
    public ApiResponse<McpConnections.Connection> enable(@RequestHeader(value="Authorization",required=false) String auth, @PathVariable String provider, @Valid @RequestBody Toggle body, HttpServletRequest request) {
        return ApiResponse.ok(connections.enable(users.require(auth), provider, body.enabled()), TraceIdFilter.current(request));
    }
    @PostMapping("/{provider}/refresh")
    public ApiResponse<McpConnections.Connection> refresh(@RequestHeader(value="Authorization",required=false) String auth, @PathVariable String provider, HttpServletRequest request) {
        return ApiResponse.ok(connections.refresh(users.require(auth), provider), TraceIdFilter.current(request));
    }
    @PostMapping("/{provider}/call")
    public ApiResponse<JsonNode> call(@RequestHeader(value="Authorization",required=false) String auth, @PathVariable String provider, @Valid @RequestBody Call body, HttpServletRequest request) {
        return ApiResponse.ok(connections.call(users.require(auth), provider, body.tool(), body.arguments()), TraceIdFilter.current(request));
    }
}
