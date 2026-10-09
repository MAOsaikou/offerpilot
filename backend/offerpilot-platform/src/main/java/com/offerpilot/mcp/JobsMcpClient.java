package com.offerpilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

/** Scoped client for the bundled stateless jobs server; never calls its debug REST endpoints. */
@Component
public class JobsMcpClient {
    private final ObjectMapper json;
    private final URI endpoint;
    @Value("${offerpilot.mcp.jobs-timeout-ms:${JOBS_LIVE_TIMEOUT_MS:10000}}")
    private long timeoutMs = 10000;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public JobsMcpClient(ObjectMapper json, @Value("${offerpilot.mcp.jobs-url:http://localhost:7001}") String base) {
        this.json = json;
        this.endpoint = URI.create(base.replaceAll("/+$", "") + (base.replaceAll("/+$", "").endsWith("/mcp") ? "" : "/mcp"));
    }
    public JsonNode call(String tool, Map<String, Object> arguments) {
        if (!java.util.Set.of("list_sites", "search_jobs", "get_job_detail").contains(tool))
            throw new IllegalArgumentException("Unsupported jobs tool");
        try {
            long deadline = System.nanoTime() + Duration.ofMillis(Math.max(1, timeoutMs)).toNanos();
            var init = exchange(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                "protocolVersion", "2025-03-26", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "offerpilot-backend", "version", "0.1.0"))), "2025-03-26", deadline);
            String version = result(init, 1).path("protocolVersion").asText();
            if (!java.util.Set.of("2025-03-26", "2025-06-18", "2025-11-25").contains(version)) throw new IllegalStateException();
            exchange(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), version, deadline);
            JsonNode output = result(exchange(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", arguments)), version, deadline), 2);
            if (output.path("isError").asBoolean()) throw new IllegalStateException();
            for (var content : output.path("content")) {
                if ("text".equals(content.path("type").asText())) return json.readTree(content.path("text").asText());
            }
            throw new IllegalStateException();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception ex) { throw unavailable(); }
    }
    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.TOOL_FAILED, "企业岗位服务暂不可用，请稍后重试；本地运行时请确认 jobs-mcp 已启动");
    }
    private JsonNode result(JsonNode value, int id) {
        if (!"2.0".equals(value.path("jsonrpc").asText()) || value.path("id").asInt(-1) != id
                || value.has("error") || !value.has("result")) throw new IllegalStateException();
        return value.get("result");
    }
    private JsonNode exchange(Map<String, Object> body, String version, long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining))
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", version).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        // The bundled server uses finite JSON responses. A size cap prevents unbounded tool payloads.
        var pending = http.sendAsync(request, info -> new LimitedBody());
        HttpResponse<byte[]> response;
        try { response = pending.get(Math.max(1, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS); }
        catch (Exception ex) { pending.cancel(true); throw ex; }
        if (response.statusCode() == 202 && !body.containsKey("id")) return json.createObjectNode();
        if (response.statusCode() != 200 || response.body().length > 2_000_000) throw new IllegalStateException();
        String text = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
        if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
            for (String frame : text.replace("\r\n", "\n").split("\n\n")) {
                String data = frame.lines().filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).stripLeading()).collect(java.util.stream.Collectors.joining("\n"));
                if (!data.isBlank()) {
                    JsonNode node = json.readTree(data);
                    if (node.path("id").asInt(-1) == ((Number) body.get("id")).intValue()) return node;
                }
            }
            throw new IllegalStateException();
        }
        return json.readTree(text);
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription=value; value.request(1); }
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(bytes.size()+buffer.remaining()>2_000_000) { subscription.cancel(); result.completeExceptionally(new IllegalStateException("MCP response too large")); return; }
                byte[] chunk=new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
