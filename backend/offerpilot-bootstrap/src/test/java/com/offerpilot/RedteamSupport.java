package com.offerpilot;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.llm.ModelGateway;
import com.sun.net.httpserver.HttpServer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

final class RedteamSupport {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PHONE = "13800138000", EMAIL = "fictional@example.test", ID = "110101199001010015";
    record Case(String id, String route, String category, String text, boolean live) {}
    static List<Case> cases() throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(new ClassPathResource("redteam/redteam-v1.jsonl").getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().filter(s -> !s.isBlank()).map(s -> {
                try { return JSON.readValue(s, Case.class); } catch (IOException ex) { throw new UncheckedIOException(ex); }
            }).toList();
        }
    }
    static ModelGateway gateway(String base, String key) {
        return new ModelGateway(new OfferPilotProperties(null,null,null,null,null,
                new OfferPilotProperties.Llm("FLASH", Map.of("FLASH",new OfferPilotProperties.Llm.Tier("deepseek","deepseek-flash", Duration.ofSeconds(60),true,"disabled",2048)), Map.of()),null,null),
                new MockEnvironment().withProperty("spring.ai.deepseek.base-url",base).withProperty("spring.ai.deepseek.api-key",key));
    }
    /** Records ONLY JSON payloads. Never records headers, authentication or raw live output. */
    static final class ModelStub implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        volatile Function<JsonNode,String> reply = body -> "桩回复";
        ModelStub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
                server.createContext("/chat/completions", exchange -> {
                    var body = JSON.readTree(exchange.getRequestBody()); requests.add(body);
                    String output = reply.apply(body);
                    boolean stream = body.path("stream").asBoolean();
                    exchange.getResponseHeaders().set("Content-Type",stream ? "text/event-stream; charset=utf-8" : "application/json");
                    exchange.sendResponseHeaders(200,0);
                    try (var out = exchange.getResponseBody()) {
                        if (stream) {
                            // Split every placeholder across chunks, including one-character suffixes.
                            for (int i=0; i<output.length(); i+=3) {
                                String part = output.substring(i,Math.min(i+3,output.length()));
                                out.write(("data: "+JSON.writeValueAsString(Map.of("id","redteam-stub","model","deepseek-flash","choices",List.of(Map.of("index",0,"delta",Map.of("role","assistant","content",part)))))+"\n\n").getBytes(StandardCharsets.UTF_8));
                            }
                            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                        } else out.write(JSON.writeValueAsBytes(Map.of("id","redteam-stub","choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content",output),"finish_reason","stop")))));
                    }
                });
                server.start();
            } catch (IOException ex) { throw new UncheckedIOException(ex); }
        }
        String base() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
    static String userText(JsonNode body) {
        var messages = body.path("messages");
        return messages.get(messages.size()-1).path("content").asText();
    }
}
