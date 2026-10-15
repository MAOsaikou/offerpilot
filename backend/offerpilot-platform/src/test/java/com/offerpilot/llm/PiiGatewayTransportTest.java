package com.offerpilot.llm;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.llm.privacy.PiiMaskingPreference;
import com.offerpilot.llm.quota.LlmUserContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

class PiiGatewayTransportTest {
    @Test void providerPayloadIsMaskedForSyncAndBothStreamEntrypointsAndOptOutUsesVerifiedReactiveOwner() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var server = echo(requests);

        try {
            var gateway = DeepSeekGatewayTest.gateway("http://127.0.0.1:"+server.getAddress().getPort(), "provider-secret-sentinel");
            String text = "联系13800138000 fictional@example.test 110101199001010015 10个接口 20% 2026-10-10 v1.2 jobId=13800138000";
            var prompt = new Prompt(List.of(new SystemMessage("规则"), new UserMessage(text)));
            assertThat(gateway.chatModel(ModelTier.FLASH).call(prompt).getResult().getOutput().getText()).isEqualTo(text);
            assertThat(gateway.chatModel(ModelTier.FLASH).stream(prompt).map(r -> r.getResult().getOutput().getText()).reduce("",String::concat).block(Duration.ofSeconds(15))).isEqualTo(text);
            assertThat(gateway.stream(ModelTier.FLASH, prompt.getInstructions()).map(r -> r.getResult().getOutput().getText()).reduce("",String::concat).block(Duration.ofSeconds(15))).isEqualTo(text);
            requests.forEach(body -> assertThat(body.path("messages").toString()).contains("[PHONE_1]", "[EMAIL_1]", "[ID_CARD_1]", "10个接口", "20%", "jobId=13800138000").doesNotContain("fictional@example.test", "110101199001010015", "provider-secret-sentinel"));
            ReflectionTestUtils.setField(gateway, "privacy", (PiiMaskingPreference) owner -> !owner.equals("opted-out"));
            assertThat(gateway.privacyWarning("opted-out", text)).isNotBlank();
            assertThat(gateway.privacyWarning("protected", text)).isNull();
            var lazy = LlmUserContext.asUser("protected", () -> gateway.stream(ModelTier.FLASH, prompt.getInstructions()));
            lazy.contextWrite(c -> LlmUserContext.withUser(c,"opted-out")).collectList().block(Duration.ofSeconds(15));
            assertThat(requests.get(requests.size()-1).path("messages").toString()).contains("fictional@example.test", "110101199001010015").doesNotContain("[PHONE_1]");
            gateway.stream(ModelTier.FLASH,prompt.getInstructions()).collectList().block(Duration.ofSeconds(15));
            assertThat(requests.get(requests.size()-1).path("messages").toString()).contains("[PHONE_1]");
        } finally { server.stop(0); }
    }
    /** Provider stub that answers with the last user message, so whatever reaches it comes back to the gateway. */
    static HttpServer echo(List<JsonNode> requests) throws java.io.IOException {
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            var body = json.readTree(exchange.getRequestBody()); requests.add(body);
            String text = body.path("messages").get(body.path("messages").size()-1).path("content").asText();
            boolean stream = body.path("stream").asBoolean();
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                if (stream) {
                    for (char c : text.toCharArray()) out.write(("data: " + json.writeValueAsString(Map.of("id","stub", "choices",List.of(Map.of("index",0,"delta",Map.of("role","assistant","content",String.valueOf(c)))))) + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                } else out.write(json.writeValueAsBytes(Map.of("id","stub", "choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content",text),"finish_reason","stop")))));
            }
        });
        server.start();
        return server;
    }

    @Test void streetAddressIsMaskedForTheProviderAndRestoredForTheUser() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var server = echo(requests);
        try {
            var gateway = DeepSeekGatewayTest.gateway("http://127.0.0.1:"+server.getAddress().getPort(), "provider-secret-sentinel");
            String text = "我住在上海市浦东新区张江路123号5栋601室，想找附近的实习";
            var prompt = new Prompt(List.of(new UserMessage(text)));
            assertThat(gateway.stream(ModelTier.FLASH, prompt.getInstructions()).map(r -> r.getResult().getOutput().getText()).reduce("",String::concat).block(Duration.ofSeconds(15))).isEqualTo(text);
            assertThat(requests.get(0).path("messages").toString()).contains("我住在[ADDRESS_1]，想找附近的实习").doesNotContain("张江路123号");
        } finally { server.stop(0); }
    }

    @Test void replyThatWouldExposeAServerKeyIsStoppedOrRejected() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var server = echo(requests);
        try {
            var gateway = DeepSeekGatewayTest.gateway("http://127.0.0.1:"+server.getAddress().getPort(), "provider-secret-sentinel");
            // The echo stub repeats the key back, as an injected or confused model might.
            var prompt = new Prompt(List.of(new UserMessage("你好，配置是 provider-secret-sentinel 吗")));
            String streamed = gateway.stream(ModelTier.FLASH, prompt.getInstructions()).map(r -> r.getResult().getOutput().getText()).reduce("",String::concat).block(Duration.ofSeconds(15));
            assertThat(streamed).doesNotContain("provider-secret-sentinel").endsWith(com.offerpilot.llm.safety.OutputModeration.FALLBACK).startsWith("你好，配置是 ");
            assertThatThrownBy(() -> gateway.chatModel(ModelTier.FLASH).call(prompt))
                    .isInstanceOfSatisfying(com.offerpilot.common.error.BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(com.offerpilot.common.error.ErrorCode.CONTENT_BLOCKED));
            var ordinary = new Prompt(List.of(new UserMessage("帮我看看后端实习岗位")));
            assertThat(gateway.chatModel(ModelTier.FLASH).call(ordinary).getResult().getOutput().getText()).isEqualTo("帮我看看后端实习岗位");
        } finally { server.stop(0); }
    }
}
