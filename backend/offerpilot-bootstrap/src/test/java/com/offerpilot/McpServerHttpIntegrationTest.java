package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import com.offerpilot.mcp.JobsMcpClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "offerpilot.jobs.source-mode=AUTO", "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
        "spring.ai.mcp.server.enabled=true", "offerpilot.mcp.server-api-key=mcp-integration-test-only",
        "offerpilot.resume.worker-enabled=false", "offerpilot.demo.enabled=false"})
@ActiveProfiles({"test", "mock-llm"})
@Testcontainers
class McpServerHttpIntegrationTest {
    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ToolCallbackProvider provider;
    @MockBean ModelGateway gateway;
    @MockBean JobsMcpClient upstream;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }

    @BeforeEach
    void fixtures() throws Exception {
        when(gateway.defaultTier()).thenReturn(ModelTier.FLASH);
        when(gateway.modelName(any())).thenReturn("offline-fixture");
        when(gateway.stream(any(), anyList(), anyDouble())).thenAnswer(invocation -> {
            List<org.springframework.ai.chat.messages.Message> messages = invocation.getArgument(1);
            boolean matching = json.readTree(messages.get(1).getText()).has("jobText");
            String output = matching ? """
                    {"summary":"Java经验提供依据","requirements":[{"requirement":"Java","jdQuote":"Java",
                    "status":"MET","resumePath":"/rawText","resumeQuote":"Java","reason":"有原文依据","suggestion":"核实岗位职责"}]}
                    """ : """
                    {"summary":"Java经历清晰","strengths":[{"message":"有技术经历","evidence":[{"path":"/rawText","quote":"Java"}]}],
                    "findings":[{"ruleId":"RESULT","kind":"OBSERVATION","priority":"HIGH","evidence":[{"path":"/rawText","quote":"Java"}],
                    "reason":"补充验证方式","suggestion":"说明验证依据","questions":[]}]}
                    """;
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(output)))));
        });
        var payload = json.createObjectNode();
        var jobs = payload.putArray("jobs");
        for (int i = 0; i < 3; i++) jobs.addObject().put("id", "mcp-fixture-" + i).put("site", "fixture")
                .put("title", "Java工程师😀").put("company", "示例公司").put("locations", "北京")
                .put("description", "中文".repeat(119) + "😀" + "详情".repeat(1000))
                .put("nature", "campus").put("url", "https://example.com/jobs/" + i);
        payload.putObject("_meta").putArray("sites").add("fixture");
        when(upstream.call(eq("search_jobs"), anyMap())).thenReturn(payload);
    }

    @Test
    void enabledServerPublishesOnlyTheThreeReadOnlySchemas() throws Exception {
        assertThat(provider.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("resume.diagnose", "jobs.search", "jobs.match");
        for (var callback : provider.getToolCallbacks()) {
            assertThat(callback.getToolDefinition().description()).contains("只读", "Read-only");
            var schema = json.readTree(callback.getToolDefinition().inputSchema());
            assertThat(schema.path("properties").size()).isPositive();
            assertThat(schema.path("required").toString()).contains(
                    callback.getToolDefinition().name().equals("jobs.search") ? "query" : "resumeText");
        }
    }

    @Test
    void httpRejectsMissingAndWrongKeysOnEveryTransportPath() throws Exception {
        var client = HttpClient.newHttpClient();
        for (String path : List.of("/sse", "/mcp", "/mcp/message")) {
            for (String key : List.of("", "wrong-test-key")) {
                var request = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(10));
                if (!key.isEmpty()) request.header("X-API-Key", key);
                if (!path.equals("/sse")) request.POST(HttpRequest.BodyPublishers.ofString("{}"));
                var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(response.body()).contains("X-API-Key");
            }
        }
        verifyNoInteractions(upstream);
    }

    @Test
    void realSseInitializeListAndCallDoNotWriteUserBusinessTables() throws Exception {
        var before = businessCounts();
        var transport = HttpClientSseClientTransport.builder(base())
                .customizeRequest(request -> request.header("X-API-Key", "mcp-integration-test-only")).build();
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15)).build()) {
            assertThat(client.initialize().serverInfo().name()).isEqualTo("offerpilot");
            var tools = client.listTools().tools();
            assertThat(tools).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("resume.diagnose", "jobs.search", "jobs.match");
            for (var tool : tools) {
                assertThat(tool.description()).contains("只读", "Read-only");
                assertThat(tool.inputSchema().properties()).isNotEmpty();
            }
            JsonNode diagnosis = call(client, "resume.diagnose", Map.of("resumeText", "Java项目经验", "jobDescription", "Java开发"));
            assertThat(diagnosis.path("overallScore").isNull()).isTrue();
            assertThat(diagnosis.path("strengthsCount").asInt()).isEqualTo(1);
            assertThat(diagnosis.path("topIssues").get(0).path("severity").asText()).isEqualTo("HIGH");
            assertThat(diagnosis.path("jobMatch").path("score").asInt()).isEqualTo(100);
            assertThat(diagnosis.has("findings")).isFalse();
            JsonNode basic = call(client, "resume.diagnose", Map.of("resumeText", "Java项目经验"));
            assertThat(basic.has("jobMatch")).isFalse();
            JsonNode match = call(client, "jobs.match", Map.of("resumeText", "Java项目经验", "jobDescription", "Java开发"));
            assertThat(match.path("score").asInt()).isEqualTo(100);
            assertThat(match.path("requirements").get(0).path("resumePath").asText()).isEqualTo("/rawText");
            JsonNode search = call(client, "jobs.search", Map.of("query", " Java ", "nature", "CAMPUS", "limit", 2));
            assertThat(search.path("items").size()).isEqualTo(2);
            assertThat(search.path("items").get(0).path("excerpt").asText()).isEqualTo("中文".repeat(119) + "…");
            assertThat(search.path("items").get(0).has("description")).isFalse();
            assertThat(search.path("items").get(0).path("title").asText()).isEqualTo("Java工程师😀");
            assertThat(search.toString().length()).isLessThan(1600);
            assertThat(search.path("meta").path("sites").get(0).asText()).isEqualTo("fixture");
            assertThat(search.path("truncated").asBoolean()).isTrue();
            JsonNode defaultSearch = call(client, "jobs.search", Map.of("query", "Java"));
            assertThat(defaultSearch.path("limit").asInt()).isEqualTo(10);
            var invalid = client.callTool(new McpSchema.CallToolRequest("jobs.search", Map.of("query", " ")));
            assertThat(invalid.isError()).isTrue();
            var wrongArg = client.callTool(new McpSchema.CallToolRequest("jobs.search", Map.of("keyword", "Java")));
            assertThat(wrongArg.isError()).isTrue();
            assertThat(((McpSchema.TextContent)wrongArg.content().get(0)).text()).contains("query is required");
        }
        verify(upstream).call("search_jobs", Map.of("query", "Java", "nature", "campus", "limit", 30));
        verify(upstream).call("search_jobs", Map.of("query", "Java", "nature", "all", "limit", 30));
        assertThat(businessCounts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE site='fixture'", Integer.class)).isEqualTo(3);
    }

    @Test
    void realMcpSearchAutoFallsBackWhenUpstreamIsUnavailable() throws Exception {
        when(upstream.call(eq("search_jobs"), anyMap())).thenThrow(
                new com.offerpilot.common.error.BusinessException(com.offerpilot.common.error.ErrorCode.TOOL_FAILED));
        var transport = HttpClientSseClientTransport.builder(base())
                .customizeRequest(request -> request.header("X-API-Key", "mcp-integration-test-only")).build();
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15)).build()) {
            client.initialize();
            var result = call(client, "jobs.search", Map.of("query", "Java", "site", "bytedance", "limit", 1));
            assertThat(result.path("items")).hasSize(1);
            assertThat(result.at("/meta/source").asText()).isEqualTo("LOCAL_SNAPSHOT");
            assertThat(result.at("/meta/fallbackReason").asText()).isEqualTo("LIVE_UNAVAILABLE");
            assertThat(result.at("/meta/snapshotDate").asText()).isEqualTo("2026-10-10");
        }
    }

    private JsonNode call(io.modelcontextprotocol.client.McpSyncClient client, String name, Map<String, Object> args) throws Exception {
        var result = client.callTool(new McpSchema.CallToolRequest(name, args));
        assertThat(result.isError()).isFalse();
        return json.readTree(((McpSchema.TextContent) result.content().get(0)).text());
    }

    private Map<String, Long> businessCounts() {
        var counts = new LinkedHashMap<String, Long>();
        for (String table : List.of("applications", "career_profiles", "resumes", "resume_versions", "resume_patch_batches", "resume_optimizations", "resume_diagnoses", "job_matches"))
            counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        return counts;
    }

    private String base() { return "http://127.0.0.1:" + port; }
}
