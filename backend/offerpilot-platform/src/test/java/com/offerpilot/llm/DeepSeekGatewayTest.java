package com.offerpilot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.config.OfferPilotProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekGatewayTest {
    record Input(int value) {}
    static final ObjectMapper JSON = new ObjectMapper();
    @Test void sameOriginReusesConnectionAcrossModelClientsAndSyncAndStreamingTransports() throws Exception {
        var ports = new java.util.HashSet<Integer>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pool", exchange -> {
            synchronized (ports) { ports.add(exchange.getRemoteAddress().getPort()); }
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/pool";
            var classification = new DeepSeekRequestSupport("disabled", Duration.ofSeconds(3));
            var reply = new DeepSeekRequestSupport("disabled", Duration.ofSeconds(5));
            assertThat(classification.restClient().build().get().uri(uri).retrieve().body(String.class)).isEqualTo("{}");
            assertThat(reply.webClient().build().get().uri(uri).retrieve().bodyToMono(String.class).block(Duration.ofSeconds(3))).isEqualTo("{}");
            assertThat(reply.restClient().build().get().uri(uri).retrieve().body(String.class)).isEqualTo("{}");
            synchronized (ports) { assertThat(ports).hasSize(1); }
        } finally { server.stop(0); }
    }
    static ModelGateway gateway(String base, String key) {
        return new ModelGateway(new OfferPilotProperties(null, null, null, null, null,
                new OfferPilotProperties.Llm("FLASH", Map.of(
                        "FLASH", new OfferPilotProperties.Llm.Tier("deepseek", "deepseek-flash", Duration.ofSeconds(60), true, "disabled", 8192),
                        "PRO", new OfferPilotProperties.Llm.Tier("deepseek", "deepseek-v4-pro", Duration.ofSeconds(120), false, "enabled", 32768),
                        "VISION_ALT", new OfferPilotProperties.Llm.Tier("deepseek", "deepseek-flash", Duration.ofSeconds(90), true)), Map.of()), null, null),
                new MockEnvironment().withProperty("offerpilot.llm.injection-classifier-enabled", "false").withProperty("spring.ai.deepseek.base-url", base).withProperty("spring.ai.deepseek.api-key", key));
    }
    @Test void productionSseDecoderReassemblesUtf8EvenWhenEveryByteIsASeparateBuffer() {
        String content = "有框架经验😀，熟悉中文流式传输";
        byte[] bytes = ("data: {\"id\":\"stub\",\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\""
                + content + "\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        var support = new DeepSeekRequestSupport("disabled", Duration.ofSeconds(60));
        reactor.core.publisher.Flux<org.springframework.core.io.buffer.DataBuffer> buffers = reactor.core.publisher.Flux.range(0, bytes.length).map(i ->
                new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(new byte[]{bytes[i]}));
        var web = support.webClient().exchangeFunction(request -> reactor.core.publisher.Mono.just(
                org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK)
                        .header("Content-Type", "text/event-stream;charset=UTF-8").body(buffers).build()));
        var api = org.springframework.ai.deepseek.api.DeepSeekApi.builder().baseUrl("http://unused.invalid")
                .apiKey("isolated-test-key").webClientBuilder(web).restClientBuilder(support.restClient()).build();
        var request = new org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionRequest(List.of(
                new org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage("中文", org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage.Role.USER)), "deepseek-flash", 0.0, true);
        String result = api.chatCompletionStream(request).map(chunk -> chunk.choices().get(0).delta().content())
                .reduce("", String::concat).block(Duration.ofSeconds(5));
        assertThat(result).isEqualTo(content).doesNotContain("\uFFFD");
    }

    @Test void bothTiersUseTheirModelThinkingBudgetAndOnlyContentForSyncStreamAndToolLoops() throws Exception {
        var requests = new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            var body = JSON.readTree(exchange.getRequestBody());
            requests.add(body);
            boolean tools = !body.path("tools").isEmpty();
            boolean result = body.path("messages").toString().contains("tool_call_id");
            String message = tools && !result
                    ? "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"doubleValue\",\"arguments\":\"{\\\"value\\\":21}\"}}]}"
                    : "{\"role\":\"assistant\",\"content\":\"{\\\"answer\\\":42}\",\"reasoning_content\":\"private-reasoning-sentinel\"}";
            String finish = tools && !result ? "tool_calls" : "stop";
            boolean stream = body.path("stream").asBoolean();
            String response = "{\"id\":\"stub\",\"model\":\"" + body.path("model").asText() + "\",\"choices\":[{\"index\":0,\"" + (stream ? "delta" : "message") + "\":" + message + ",\"finish_reason\":\"" + finish + "\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) { out.write((stream ? "data: " + response + "\n\ndata: [DONE]\n\n" : response).getBytes(StandardCharsets.UTF_8)); }
        });
        server.start();
        try {
            var gateway = gateway("http://127.0.0.1:" + server.getAddress().getPort(), "isolated-key");
            assertThat(gateway.defaultTier()).isEqualTo(ModelTier.FLASH);
            assertThat(gateway.supportsVision(ModelTier.FLASH)).isTrue();
            assertThat(gateway.supportsVision(ModelTier.PRO)).isFalse();
            assertThat(gateway.supportsVision(ModelTier.VISION_ALT)).isTrue();
            var image = UserMessage.builder().text("image").media(new org.springframework.ai.content.Media(
                    org.springframework.util.MimeTypeUtils.IMAGE_PNG, new org.springframework.core.io.ByteArrayResource(new byte[]{1}))).build();
            for (var vision : List.of(ModelTier.FLASH, ModelTier.VISION_ALT)) {
                assertThat(gateway.stream(vision, List.of(image)).collectList().block()).isNotEmpty();
                assertThat(requests.get(requests.size() - 1).path("messages").toString()).contains("image_url");
                assertThat(requests.get(requests.size() - 1).path("thinking").path("type").asText()).isEqualTo("disabled");
            }
            for (var tier : List.of(ModelTier.FLASH, ModelTier.PRO)) {
                requests.clear();
                assertThat(gateway.chatModel(tier).call(new Prompt("json")).getResult().getOutput().getText()).isEqualTo("{\"answer\":42}");
                String streamed = gateway.stream(tier, List.of(new UserMessage("json"))).map(r -> r.getResult().getOutput().getText()).collectList().block().stream().reduce("", String::concat);
                assertThat(JSON.readTree(streamed).path("answer").asInt()).isEqualTo(42);
                for (var request : requests) {
                    assertThat(request.path("model").asText()).isEqualTo(gateway.modelName(tier));
                    assertThat(request.path("thinking").path("type").asText()).isEqualTo(tier == ModelTier.PRO ? "enabled" : "disabled");
                    assertThat(request.path("max_tokens").asInt()).isEqualTo(tier == ModelTier.PRO ? 32768 : 8192);
                }
                for (boolean stream : List.of(false, true)) {
                    requests.clear();
                    var count = new AtomicInteger();
                    var tool = FunctionToolCallback.builder("doubleValue", (Input input) -> { count.incrementAndGet(); return input.value() * 2; })
                            .description("Double a value").inputType(Input.class).build();
                    var prompt = ChatClient.create(gateway.chatModel(tier)).prompt().user("Double 21").toolCallbacks(tool);
                    String text = stream ? prompt.stream().content().collectList().block().stream().reduce("", String::concat) : prompt.call().content();
                    assertThat(text).isEqualTo("{\"answer\":42}");
                    assertThat(count.get()).isEqualTo(1);
                    assertThat(requests).hasSize(2);
                    requests.forEach(request -> assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled"));
                    assertThat(requests.get(1).path("messages").toString()).contains("tool_call_id", "42");
                }
            }
        } finally { server.stop(0); }
    }
}
