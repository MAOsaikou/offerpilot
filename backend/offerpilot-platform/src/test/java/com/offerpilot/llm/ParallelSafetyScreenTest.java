package com.offerpilot.llm;

import com.offerpilot.config.OfferPilotProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class ParallelSafetyScreenTest {
    @Test void cleanBuffersProviderUntilVerdictAndSerialFlagRestoresOldOrdering() throws Exception {
        for (boolean parallel : List.of(true, false)) try (var stub = new Stub(parallel)) {
            var events = new CopyOnWriteArrayList<String>();
            var done = new CountDownLatch(1);
            var subscription = stub.reply().subscribe(r -> events.add(r.getResult().getOutput().getText()),
                    error -> { throw new AssertionError(error); }, done::countDown);
            assertThat(stub.classifying.await(2, TimeUnit.SECONDS)).isTrue();
            if (parallel) assertThat(stub.generating.await(2, TimeUnit.SECONDS)).isTrue();
            else assertThat(stub.generating.getCount()).isEqualTo(1);
            assertThat(events).isEmpty();
            stub.verdict.tryEmitValue("{\"injection\":false,\"categories\":[]}");
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(events).containsExactly("unreleased");
            assertThat(stub.requests).hasSize(1);
            subscription.dispose();
        }
    }

    @Test void injectionDiscardsCandidateAndMatchesBlockingWarningPathExactly() throws Exception {
        for (boolean parallel : List.of(true, false)) try (var stub = new Stub(parallel)) {
            var result = stub.reply().collectList().toFuture();
            assertThat(stub.classifying.await(2, TimeUnit.SECONDS)).isTrue();
            if (parallel) assertThat(stub.generating.await(2, TimeUnit.SECONDS)).isTrue();
            stub.verdict.tryEmitValue("{\"injection\":true,\"categories\":[\"OVERRIDE\"]}");
            assertThat(result.get(3, TimeUnit.SECONDS)).extracting(r -> r.getResult().getOutput().getText())
                    .containsExactly("screened");
            assertThat(stub.requests).hasSize(parallel ? 2 : 1);
            assertThat(stub.requests.get(stub.requests.size() - 1)).contains("疑似提示注入", "OVERRIDE");
        }
    }

    @Test void timeoutStillFailsOpenAtExistingBudget() throws Exception {
        try (var stub = new Stub(true)) {
            StepVerifier.create(stub.reply()).expectNextMatches(r -> r.getResult().getOutput().getText().equals("unreleased"))
                    .expectComplete().verify(Duration.ofSeconds(4));
            assertThat(stub.classifierCancelled).isTrue();
            assertThat(stub.requests).hasSize(1);
        }
    }

    @Test void cancellingWhileVerdictPendingCancelsClassifierAndReleasesNoChunks() throws Exception {
        try (var stub = new Stub(true)) {
            var events = new CopyOnWriteArrayList<String>();
            var subscription = stub.reply().subscribe(r -> events.add(r.getResult().getOutput().getText()));
            assertThat(stub.classifying.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(stub.generating.await(2, TimeUnit.SECONDS)).isTrue();
            subscription.dispose();
            assertThat(stub.cancelled.await(2, TimeUnit.SECONDS)).isTrue();
            stub.verdict.tryEmitValue("{\"injection\":false,\"categories\":[]}");
            assertThat(events).isEmpty();
        }
    }

    @Test void turnScopeHoldsEffectsAndReusesExactFragmentAcrossFinalPrompt() throws Exception {
        try (var stub = new Stub(true)) {
            var effect = new AtomicBoolean();
            var messages = List.<org.springframework.ai.chat.messages.Message>of(new UserMessage("ordinary project question"));
            var completed = stub.gateway.withSafetyScreen(messages, ModelGateway.beforeEffects()
                    .doOnSuccess(ignored -> effect.set(true)).thenMany(stub.gateway.stream(ModelTier.FLASH, messages)))
                    .collectList().toFuture();
            assertThat(stub.classifying.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(effect).isFalse();
            assertThat(stub.generating.getCount()).isEqualTo(1);
            stub.verdict.tryEmitValue("{\"injection\":false,\"categories\":[]}");
            assertThat(completed.get(3, TimeUnit.SECONDS)).hasSize(1);
            assertThat(effect).isTrue();
            assertThat(stub.classificationCount.get()).isEqualTo(1);
        }
    }

    private static final class Stub implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();
        final Sinks.One<String> verdict = Sinks.one();
        final CountDownLatch classifying = new CountDownLatch(1), generating = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        final AtomicBoolean classifierCancelled = new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger classificationCount = new java.util.concurrent.atomic.AtomicInteger();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final ModelGateway gateway;

        Stub(boolean parallel) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/v1/chat/completions", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(body);
                generating.countDown();
                String text = body.contains("疑似提示注入") ? "screened" : "unreleased";
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    output.write(("data: {\"id\":\"stub\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text
                            + "\"}}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                }
            });
            server.start();
            var tier = new OfferPilotProperties.Llm.Tier("custom", "stub", Duration.ofSeconds(5), true);
            var properties = new OfferPilotProperties(null, null, null, null, null,
                    new OfferPilotProperties.Llm("FLASH", Map.of("FLASH", tier), Map.of()), null, null);
            var env = new MockEnvironment().withProperty("offerpilot.agent.parallel-safety-screen", String.valueOf(parallel))
                    .withProperty("offerpilot.llm.providers.custom.base-url", "http://127.0.0.1:" + server.getAddress().getPort())
                    .withProperty("offerpilot.llm.providers.custom.api-key", "isolated-test-key");
            gateway = new ModelGateway(properties, env) {
                @Override protected Mono<String> classifyInjection(Prompt prompt) {
                    classificationCount.incrementAndGet();
                    classifying.countDown();
                    return verdict.asMono().doOnCancel(() -> { classifierCancelled.set(true); cancelled.countDown(); });
                }
            };
        }

        reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> reply() {
            return gateway.stream(ModelTier.FLASH, List.of(new UserMessage("ordinary project question")));
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
