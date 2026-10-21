package com.offerpilot.llm.quota;

import com.offerpilot.common.error.*;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.llm.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LlmQuotaGatewayTest {
    HttpServer provider;
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean reportUsage = new AtomicBoolean(true);
    MockEnvironment environment;
    ModelGateway gateway;
    LlmQuotaLimiter limiter;
    @BeforeEach void setup() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean streaming = body.contains("\"stream\":true");
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                String response = streaming ? """
                        data: {"id":"stub","model":"stub","choices":[{"index":0,"delta":{"content":"ok"}}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}

                        data: {"id":"stub","model":"stub","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}

                        data: [DONE]

                        """ : """
                        {"id":"stub","model":"stub","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}
                        """;
                if (!reportUsage.get()) response = response.replaceAll(",\"usage\":\\{[^}]*\\}", "");
                out.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });
        provider.start();
        var limits = new OfferPilotProperties.RateLimit(4, 2, 2, 100, 12, 15, 20, 20, 20, 20);
        var tier = new OfferPilotProperties.Llm.Tier("custom", "stub", Duration.ofSeconds(5), true);
        var properties = new OfferPilotProperties(null, null, null, null, limits,
                new OfferPilotProperties.Llm("FLASH", Map.of("FLASH", tier, "PRO", tier), Map.of()), null, null);
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T12:00:30Z"), ZoneOffset.UTC);
        limiter = new LlmQuotaLimiter(owner -> new LlmQuotaIdentity.Policy(owner.startsWith("guest"), owner.startsWith("guest") ? 12 : 100),
                usageStore = new MemoryLlmUsageStore(clock), limits, clock);
        environment = new MockEnvironment().withProperty("offerpilot.llm.injection-classifier-enabled", "false").withProperty("offerpilot.llm.providers.custom.api-key", "isolated-stub-key")
                .withProperty("offerpilot.llm.providers.custom.base-url", "http://127.0.0.1:" + provider.getAddress().getPort());
        gateway = new ModelGateway(properties, environment, limiter);
    }
    MemoryLlmUsageStore usageStore;
    /** Usage is recorded asynchronously after the stream completes; wait for it instead of racing the scheduler. */
    void awaitTokens(String owner, long atLeast) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (usageStore.tokens(owner, "2026-10-09") < atLeast && System.nanoTime() < deadline) Thread.onSpinWait();
    }
    @AfterEach void stop() { provider.stop(0); }
    void stream(String owner) {
        gateway.stream(ModelTier.FLASH, List.of(new UserMessage("hello")))
                .contextWrite(context -> LlmUserContext.withUser(context, owner)).collectList().block(Duration.ofSeconds(5));
    }
    @Test void legacyCheapAndFlashShareTheSameOwnerQuota() {
        limiter.check("guest", ModelTier.from("CHEAP"), true);
        stream("guest");
        assertThat(catchThrowableOfType(() -> stream("guest"), BusinessException.class).errorCode()).isEqualTo(ErrorCode.LLM_RATE_LIMITED);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void reactiveOwnerSurvivesSchedulingAndUsageIsCountedOnce() {
        stream("guest");
        // Repeated cumulative metadata must total 6, leaving room for a second call.
        stream("guest");
        awaitTokens("guest", 12);
        var error = catchThrowableOfType(() -> stream("guest"), BusinessException.class);
        assertThat(error.errorCode()).isEqualTo(ErrorCode.DAILY_QUOTA_EXCEEDED);
        assertThat(calls.get()).isEqualTo(2);
        stream("user");
    }
    @Test void synchronousChatModelIsGuardedAndGuestProNeverCallsProvider() {
        LlmUserContext.asUser("guest", () -> {
            var model = gateway.chatModel(ModelTier.FLASH);
            model.call(new Prompt("hello"));
            model.call(new Prompt("hello"));
            assertThat(catchThrowableOfType(() -> model.call(new Prompt("hello")), BusinessException.class).errorCode())
                    .isEqualTo(ErrorCode.DAILY_QUOTA_EXCEEDED);
            return null;
        });
        assertThat(catchThrowableOfType(() -> LlmUserContext.asUser("guest-other",
                () -> gateway.chatModel(ModelTier.PRO).call(new Prompt("hello"))), BusinessException.class).errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void requestCountIsConsumedWithoutUsageAndDirectCallsBypass() {
        reportUsage.set(false);
        stream("guest");
        stream("guest");
        assertThat(catchThrowableOfType(() -> stream("guest"), BusinessException.class).errorCode()).isEqualTo(ErrorCode.LLM_RATE_LIMITED);
        assertThat(calls.get()).isEqualTo(2);
        for (int i = 0; i < 3; i++) gateway.stream(ModelTier.PRO, List.of(new UserMessage("eval"))).collectList().block();
        gateway.chatModel(ModelTier.PRO).call(new Prompt("eval"));
        assertThat(calls.get()).isEqualTo(6);
    }
    @Test void classifierUsesVerifiedReactiveOwnerAndRecordsUsageEvenWhenJsonIsInvalid() {
        environment.withProperty("offerpilot.llm.injection-classifier-enabled", "true");
        stream("guest");
        assertThat(calls.get()).isEqualTo(2); // classifier + normal chat, never recursive
        awaitTokens("guest", 12);
        assertThat(catchThrowableOfType(() -> limiter.check("guest", ModelTier.FLASH, false), BusinessException.class)
                .errorCode()).isEqualTo(ErrorCode.DAILY_QUOTA_EXCEEDED);
        limiter.check("another-user", ModelTier.FLASH, false);
    }
    @Test void serialRollbackReservesGenerationBeforeOptionalClassifierAtLastRpmSlot() {
        environment.withProperty("offerpilot.llm.injection-classifier-enabled", "true")
                .withProperty("offerpilot.agent.parallel-safety-screen", "false");
        limiter.check("guest", ModelTier.FLASH, true); // one of two slots remains
        stream("guest"); // classifier admission fails open; already-reserved reply must still run
        assertThat(calls.get()).isEqualTo(1);
        assertThat(catchThrowableOfType(() -> stream("guest"), BusinessException.class).errorCode())
                .isEqualTo(ErrorCode.LLM_RATE_LIMITED);
    }

    @RestController static class Probe {
        final ModelGateway gateway;
        Probe(ModelGateway gateway) { this.gateway = gateway; }
        @GetMapping("/quota-probe") String call() {
            return LlmUserContext.asUser("guest", () -> gateway.chatModel(ModelTier.FLASH).call(new Prompt("hello")).getResult().getOutput().getText());
        }
    }
    @Test void httpGatewayPathReturns429BusinessCodeAndRetryAfter() throws Exception {
        var http = MockMvcBuilders.standaloneSetup(new Probe(gateway)).setControllerAdvice(new GlobalExceptionHandler()).build();
        http.perform(get("/quota-probe")).andExpect(status().isOk());
        http.perform(get("/quota-probe")).andExpect(status().isOk());
        http.perform(get("/quota-probe")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("code").value(40008)).andExpect(header().string("Retry-After", "43170"));
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void httpRpmRejectionReturnsSpecificBusinessCodeAndMinuteRetryAfter() throws Exception {
        var http = MockMvcBuilders.standaloneSetup(new Probe(gateway)).setControllerAdvice(new GlobalExceptionHandler()).build();
        limiter.check("guest", ModelTier.FLASH, true);
        limiter.check("guest", ModelTier.FLASH, true);
        http.perform(get("/quota-probe")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("code").value(40011)).andExpect(header().string("Retry-After", "30"));
        assertThat(calls.get()).isZero();
    }
}
