package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.event.AgentEvent;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ToolEventTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    @Test void toolEventsCarryTypedSourceDurationAndSafeStructuredJsonAndRoundTrip() throws Exception {
        var args = Map.of("query", "联系 someone@example.com 或 13800138000", "nested", Map.of("apiKey", "private-value"));
        var call = new AgentEvent.ToolCall("tool.call", 1, "run", Instant.now(), "s", "c", "search", args, "mcp:jobs-mcp", AgentEvent.ToolAccess.READ);
        var result = new AgentEvent.ToolResult("tool.result", 2, "run", Instant.now(), "s", "c", "search", true, "找到岗位", 42, false,
                Map.of("items", java.util.List.of(Map.of("email", "someone@example.com", "Authorization", "Bearer private-value"))));
        assertThat(call.source()).isEqualTo("MCP");
        assertThat(result.source()).isEqualTo("LOCAL");
        assertThat(call.serverName()).isEqualTo("jobs-mcp");
        String encoded = json.writeValueAsString(java.util.List.of(call, result));
        assertThat(encoded).doesNotContain("someone@example.com", "13800138000", "private-value").contains("[REDACTED]", "[EMAIL_", "durationMs", "42");
        assertThat(json.readValue(json.writeValueAsString(result), AgentEvent.class)).isEqualTo(result);
        assertThat(json.readValue(json.writeValueAsString(call), AgentEvent.class)).isEqualTo(call);
        assertThat(new AgentEvent.ToolCall("", 1, "r", Instant.now(), null,"c","local",args,"local",AgentEvent.ToolAccess.READ).source()).isEqualTo("LOCAL");
    }
    @Test void capsJsonWithoutBreakingEnvelopeOrLeakingSecretFormats() throws Exception {
        var result = new AgentEvent.ToolResult("",1,"r",Instant.now(),null,"c","read",true,"ok",1,false, Map.of("large","长".repeat(20_000)));
        assertThat(result.truncated()).isTrue();
        assertThat(json.writeValueAsString(result.result()).length()).isLessThan(16_384);
        assertThat(json.valueToTree(result.result()).path("truncated").asBoolean()).isTrue();
        var secret = new AgentEvent.ToolResult("",1,"r",Instant.now(),null,"c","read",false,"sk-" + "x".repeat(30),1,false);
        assertThat(secret.resultPreview()).isEqualTo("[REDACTED]");
        assertThat(com.offerpilot.agent.event.ToolEventData.text("{\"password\":\"hidden-value\"}")).isEqualTo("[REDACTED]");
    }
}
