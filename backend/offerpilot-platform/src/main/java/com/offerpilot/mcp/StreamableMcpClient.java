package com.offerpilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** One bounded session per operation; fixed deployment endpoints only, redirects disabled. */
public final class StreamableMcpClient {
    private final ObjectMapper json;
    private final URI endpoint;
    private final String token;
    private String session;
    private String protocolVersion;
    private final HttpClient http;
    public StreamableMcpClient(ObjectMapper json, String endpoint, String token) {
        this(json, endpoint, token, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    StreamableMcpClient(ObjectMapper json, String endpoint, String token, HttpClient http) {
        this.json = json; this.endpoint = URI.create(endpoint); this.token = token == null ? "" : token; this.http = http;
    }
    public JsonNode execute(String method, Map<String,Object> params, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(Math.max(1, timeoutMs)).toNanos();
        try {
            String version = result(exchange(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                    "protocolVersion", "2025-03-26", "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "offerpilot-backend", "version", "0.1.0"))), "2025-03-26", deadline), 1)
                    .path("protocolVersion").asText();
            if (!Set.of("2025-03-26", "2025-06-18", "2025-11-25").contains(version)) throw new IllegalStateException();
            protocolVersion = version;
            exchange(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), version, deadline);
            var output = result(exchange(Map.of("jsonrpc", "2.0", "id", 2, "method", method, "params", params), version, deadline), 2);
            if (output.path("isError").asBoolean()) throw new IllegalStateException();
            if (method.equals("tools/list")) {
                var tools = json.createArrayNode();
                int page = 2;
                Set<String> cursors = new HashSet<>();
                while (true) {
                    if (!output.path("tools").isArray()) throw new IllegalStateException();
                    for (var tool : output.path("tools")) {
                        if (tools.size() >= 200) throw new IllegalStateException();
                        tools.add(tool);
                    }
                    String cursor = output.path("nextCursor").asText("");
                    if (cursor.isEmpty()) break;
                    if (!cursors.add(cursor) || ++page > 10) throw new IllegalStateException();
                    output = result(exchange(Map.of("jsonrpc", "2.0", "id", page, "method", method,
                            "params", Map.of("cursor", cursor)), version, deadline), page);
                }
                return json.createObjectNode().set("tools", tools);
            }
            return output;
        } finally { closeSession(deadline); }
    }
    private void closeSession(long deadline) {
        if (session == null || Thread.currentThread().isInterrupted()) return;
        String active = session;
        session = null;
        long remaining = Math.min(Duration.ofMillis(250).toNanos(), deadline - System.nanoTime());
        if (remaining <= 0) return;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining))
                    .header("Mcp-Session-Id", active).DELETE();
            if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
            if (protocolVersion != null) request.header("MCP-Protocol-Version", protocolVersion);
            var pending = http.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding());
            try { pending.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS); }
            catch (Exception ex) { pending.cancel(true); if (ex instanceof InterruptedException) Thread.currentThread().interrupt(); }
        } catch (RuntimeException ignored) { /* Cleanup must never replace the operation's result. */ }
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
            .header("MCP-Protocol-Version", version)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        // The bundled server uses finite JSON responses. A size cap prevents unbounded tool payloads.
        var builder = HttpRequest.newBuilder(request, (name, value) -> true);
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        if (session != null) builder.header("Mcp-Session-Id", session);
        var pending = http.sendAsync(builder.build(), info -> new LimitedBody(
                info.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"),
                body.get("id") instanceof Number id ? id.intValue() : -1));
        HttpResponse<byte[]> response;
        try { response = pending.get(Math.max(1, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS); }
        catch (Exception ex) { pending.cancel(true); throw ex; }
        response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> session = value);
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
    private final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        private final boolean sse;
        private final int id;
        LimitedBody(boolean sse, int id) { this.sse=sse; this.id=id; }
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription=value; value.request(1); }
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(bytes.size()+buffer.remaining()>2_000_000) { subscription.cancel(); result.completeExceptionally(new IllegalStateException("MCP response too large")); return; }
                byte[] chunk=new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            // Streamable HTTP may leave SSE open after the response. Finish on the matching complete frame.
            if (sse && id >= 0) {
                String text = bytes.toString(java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
                int last = text.lastIndexOf("\n\n");
                if (last >= 0) {
                    for (String frame : text.substring(0,last).split("\n\n")) {
                        String data = frame.lines().filter(line -> line.startsWith("data:"))
                                .map(line -> line.substring(5).stripLeading()).collect(java.util.stream.Collectors.joining("\n"));
                        try {
                            if (!data.isBlank() && json.readTree(data).path("id").asInt(-1) == id) {
                                subscription.cancel(); result.complete(bytes.toByteArray()); return;
                            }
                        } catch (Exception ex) { subscription.cancel(); result.completeExceptionally(ex); return; }
                    }
                }
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
