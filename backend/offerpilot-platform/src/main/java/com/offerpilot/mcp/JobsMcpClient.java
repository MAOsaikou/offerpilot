package com.offerpilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class JobsMcpClient {
    private final ObjectMapper json;
    private final String endpoint;
    @Value("${offerpilot.mcp.jobs-timeout-ms:${JOBS_LIVE_TIMEOUT_MS:6500}}")
    private long timeoutMs = 6500;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private McpAccessPolicy access;
    public JobsMcpClient(ObjectMapper json, @Value("${offerpilot.mcp.jobs-url:http://localhost:7001}") String base) {
        this.json = json;
        this.endpoint = base.replaceAll("/+$", "") + (base.replaceAll("/+$", "").endsWith("/mcp") ? "" : "/mcp");
    }
    public JsonNode call(String tool, Map<String, Object> arguments) {
        return call(tool, arguments, timeoutMs);
    }
    /** A caller's remaining budget includes initialize, initialized and tools/call. */
    public JsonNode call(String tool, Map<String, Object> arguments, long remainingMs) {
        if (!java.util.Set.of("list_sites", "search_jobs", "get_job_detail").contains(tool))
            throw new IllegalArgumentException("Unsupported jobs tool");
        if (access != null) access.requireEnabled(com.offerpilot.llm.quota.LlmUserContext.current(), "jobs");
        if (remainingMs <= 0) throw unavailable();
        try {
            var output = new StreamableMcpClient(json, endpoint, "").execute("tools/call",
                    Map.of("name", tool, "arguments", arguments), Math.min(timeoutMs, remainingMs));
            for (var content : output.path("content"))
                if ("text".equals(content.path("type").asText())) return json.readTree(content.path("text").asText());
            throw new IllegalStateException();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (Exception ex) { throw unavailable(); }
    }
    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.TOOL_FAILED, "企业岗位服务暂不可用，请稍后重试；本地运行时请确认 jobs-mcp 已启动");
    }
}
