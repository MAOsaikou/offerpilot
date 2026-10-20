package com.offerpilot.mcp;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.common.error.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.*;

/** Deployment-owned destinations: user input is never an outbound URL, header or credential. */
@Service
public class McpConnections implements McpAccessPolicy {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Environment env;
    public McpConnections(JdbcTemplate jdbc, ObjectMapper json, Environment env) {
        this.jdbc = jdbc; this.json = json; this.env = env;
    }
    public record Connection(String provider, String name, boolean added, boolean enabled, boolean configured,
                             String status, JsonNode tools, String refreshedAt) {}
    record Destination(String endpoint, String token, boolean configured) {}
    public List<Connection> list(String owner) {
        return List.of(view(owner, "jobs"), view(owner, "search"), view(owner, "github"));
    }
    Destination destination(String provider) {
        String url, token = "";
        switch (provider) {
            case "jobs" -> {
                url = env.getProperty("offerpilot.mcp.jobs-url", "http://localhost:7001").replaceAll("/+$", "");
                if (!url.endsWith("/mcp")) url += "/mcp";
            }
            case "search" -> { url = env.getProperty("offerpilot.mcp.search-url", ""); token = env.getProperty("offerpilot.mcp.search-api-key", ""); }
            case "github" -> { url = env.getProperty("offerpilot.mcp.github-url", "https://api.githubcopilot.com/mcp/"); token = env.getProperty("offerpilot.mcp.github-token", ""); }
            default -> throw invalid();
        }
        boolean valid = false;
        try {
            var uri = URI.create(url);
            // Fixed addresses use the same trust boundary as JobsMcpClient. Never follow redirects.
            valid = Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException ignored) { }
        return new Destination(url, token, valid && (!provider.equals("github") || !token.isBlank()));
    }
    private Connection view(String owner, String provider) {
        var rows = jdbc.query("SELECT enabled,tools::text,status,refreshed_at FROM user_mcp_servers WHERE user_id=? AND provider=?",
                (rs, n) -> new Connection(provider, name(provider), true, rs.getBoolean(1), true, rs.getString(3),
                        parse(rs.getString(2)), rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant().toString()), owner, provider);
        var saved = rows.isEmpty() ? new Connection(provider, name(provider), provider.equals("jobs"), provider.equals("jobs"),
                true, "NOT_REFRESHED", json.createArrayNode(), null) : rows.get(0);
        boolean configured = destination(provider).configured();
        String status = !configured ? "NOT_CONFIGURED" : !saved.added() ? "NOT_ADDED" : !saved.enabled() ? "DISABLED" : saved.status();
        return new Connection(provider, saved.name(), saved.added(), saved.enabled(), configured, status,
                status.equals("READY") ? saved.tools() : json.createArrayNode(), saved.refreshedAt());
    }
    public Connection add(String owner, String provider) {
        destination(provider); // whitelist before SQL; idempotent, never re-enables existing disabled rows
        jdbc.update("INSERT INTO user_mcp_servers(user_id,provider) VALUES (?,?) ON CONFLICT DO NOTHING", owner, provider);
        return view(owner, provider);
    }
    public Connection enable(String owner, String provider, boolean enabled) {
        add(owner, provider);
        jdbc.update("UPDATE user_mcp_servers SET enabled=? WHERE user_id=? AND provider=?", enabled, owner, provider);
        return view(owner, provider);
    }
    @Override public void requireEnabled(String owner, String provider) {
        if (owner == null) return; // public MCP server has no private owner and retains its separate API-key gate
        if (!view(owner, provider).enabled()) throw new BusinessException(ErrorCode.FORBIDDEN, "此 MCP 连接已停用");
    }
    public Connection refresh(String owner, String provider) {
        add(owner, provider);
        var connection = view(owner, provider);
        if (!connection.enabled()) return connection;
        if (!connection.configured()) {
            save(owner, provider, json.createArrayNode(), "NOT_CONFIGURED"); return view(owner, provider);
        }
        try {
            var target = destination(provider);
            var response = client(target).execute("tools/list", Map.of(), 10000);
            var tools = json.createArrayNode();
            Set<String> names = new HashSet<>();
            for (var tool : response.path("tools")) {
                String name = tool.path("name").asText();
                if (!name.matches("[A-Za-z0-9_.-]{1,128}") || !tool.path("inputSchema").isObject() || !name.equals(safe(name,128)) || !names.add(name)) continue;
                var projected = tools.addObject().put("name", name)
                        .put("description", safe(tool.path("description").asText(), 600))
                        .put("callable", callable(provider, name));
                projected.set("inputSchema", parse(safe(tool.path("inputSchema").toString(), 50000)));
                if (tools.toString().length() > 200000) throw new IllegalStateException("Tool catalogue too large");
            }
            save(owner, provider, tools, "READY");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); save(owner, provider, json.createArrayNode(), "UNAVAILABLE");
        } catch (Exception ex) { save(owner, provider, json.createArrayNode(), "UNAVAILABLE"); }
        return view(owner, provider);
    }
    public JsonNode call(String owner, String provider, String tool, JsonNode args) {
        var connection = view(owner, provider);
        if (!connection.added() || !connection.enabled() || !connection.status().equals("READY"))
            throw new BusinessException(ErrorCode.TOOL_FAILED, "请先配置、启用并刷新 MCP 连接");
        if (!callable(provider, tool) || !java.util.stream.StreamSupport.stream(connection.tools().spliterator(), false)
                .anyMatch(item -> tool.equals(item.path("name").asText()))) throw new BusinessException(ErrorCode.FORBIDDEN, "仅允许已发现的只读工具");
        if (args == null || !args.isObject() || args.toString().length() > 20000)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "工具参数须为不超过20000字符的 JSON 对象");
        try {
            var target = destination(provider);
            var result = client(target).execute("tools/call",
                    Map.of("name", tool, "arguments", json.convertValue(args, Map.class)), 10000);
            // Return a bounded, masked display copy; never pass it back into Agent instructions.
            return json.createObjectNode().put("preview", safe(result.toString(), 20000));
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception ex) { throw unavailable(); }
    }
    StreamableMcpClient client(Destination target) { return new StreamableMcpClient(json, target.endpoint(), target.token()); }
    static boolean callable(String provider, String tool) {
        return switch (provider) {
            case "jobs" -> Set.of("list_sites", "search_jobs", "get_job_detail").contains(tool);
            case "search" -> Set.of("search", "web_search", "brave_web_search", "tavily_search", "tavily-search").contains(tool);
            case "github" -> Set.of("get_me", "search_repositories", "search_code", "get_file_contents", "list_commits", "get_commit",
                    "search_issues", "issue_read", "pull_request_read", "list_branches", "search_pull_requests").contains(tool);
            default -> false;
        };
    }
    private void save(String owner, String provider, JsonNode tools, String status) {
        jdbc.update("UPDATE user_mcp_servers SET tools=CAST(? AS jsonb),status=?,refreshed_at=now() WHERE user_id=? AND provider=? AND enabled=true",
                tools.toString(), status, owner, provider);
    }
    private String safe(String value, int max) {
        for (String key : List.of("offerpilot.mcp.search-api-key", "offerpilot.mcp.github-token", "offerpilot.mcp.server-api-key")) {
            String secret = env.getProperty(key, "");
            if (!secret.isBlank()) value = value.replace(secret, "[REDACTED]");
        }
        value = value.replaceAll("(?i)(?:github_pat_|gh[pousr]_|sk-)[A-Za-z0-9_-]{8,}", "[REDACTED]");
        value = new com.offerpilot.llm.privacy.PiiMasker.Session(List.of(value)).mask(value);
        return com.offerpilot.common.text.TextBounds.truncate(value, max);
    }
    private JsonNode parse(String text) {
        try { return json.readTree(text); } catch (Exception ex) { return json.createObjectNode(); }
    }
    private static String name(String provider) { return provider.equals("jobs") ? "jobs-mcp" : provider.equals("github") ? "GitHub MCP" : "搜索 MCP"; }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择部署已登记的 jobs/search/github 连接；不接受自定义地址或凭据"); }
    private static BusinessException unavailable() { return new BusinessException(ErrorCode.TOOL_FAILED, "MCP 工具调用失败，请检查连接后重试"); }
}
