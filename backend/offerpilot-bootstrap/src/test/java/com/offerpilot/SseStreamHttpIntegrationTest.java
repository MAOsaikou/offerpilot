package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.application.ChatService;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.web.AgentController;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.error.GlobalExceptionHandler;
import com.offerpilot.config.JacksonConfig;
import com.offerpilot.resume.application.ResumeDiagnosisService;
import com.offerpilot.resume.application.ResumeOptimizationService;
import com.offerpilot.resume.web.ResumeDiagnosisController;
import com.offerpilot.resume.web.ResumeOptimizationController;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real Tomcat and HTTP, stub application/model publishers; no network providers or databases. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = SseStreamHttpIntegrationTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"offerpilot.sse.heartbeat-interval=2ms", "offerpilot.agent.reconnect-grace=3s"})
@ExtendWith(OutputCaptureExtension.class)
class SseStreamHttpIntegrationTest {
    static final int WAIT_SECONDS = 30;
    static final int CONCURRENT_REQUESTS = 96;
    static final String REQUEST_ID_HEADER = "X-Sse-Test-Id";

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class,
            org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class})
    @Import({AgentController.class, ResumeDiagnosisController.class, ResumeOptimizationController.class,
            JacksonConfig.class, GlobalExceptionHandler.class, com.offerpilot.common.web.SseStreams.class,
            com.offerpilot.agent.application.RunRelay.class})
    static class Application {
        @Bean ContainerCompletions containerCompletions() { return new ContainerCompletions(); }
        @Bean org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory>
        recycledRequestObserver(ContainerCompletions completions) {
            return factory -> factory.addConnectorCustomizers(connector -> connector.addLifecycleListener(event -> {
                if (org.apache.catalina.Lifecycle.AFTER_INIT_EVENT.equals(event.getType())) {
                    connector.getProtocolHandler().setAdapter(new org.apache.catalina.connector.CoyoteAdapter(connector) {
                        @Override public void checkRecycled(org.apache.coyote.Request request, org.apache.coyote.Response response) {
                            String id = request.getMimeHeaders().getHeader(REQUEST_ID_HEADER);
                            var completed = id == null ? null : completions.requests.get(id);
                            // A TCP reset can take the CLOSED path without asyncPostProcess/onComplete.
                            // Observe the default adapter's actual cleanup instead of assuming a callback.
                            super.checkRecycled(request, response);
                            if (completed != null) completed.complete(null);
                        }
                    });
                }
            }));
        }
        @Bean FilterRegistrationBean<Filter> completionFilter(ContainerCompletions completions) {
            var registration = new FilterRegistrationBean<Filter>((request, response, chain) -> {
                var http = (HttpServletRequest) request;
                var lifecycle = completions.requests.get(http.getHeader(REQUEST_ID_HEADER));
                chain.doFilter(new HttpServletRequestWrapper(http) {
                    @Override public AsyncContext startAsync() { return observe(super.startAsync()); }
                    @Override public AsyncContext startAsync(ServletRequest req, ServletResponse res) {
                        return observe(super.startAsync(req, res));
                    }
                    private AsyncContext observe(AsyncContext context) {
                        context.addListener(new AsyncListener() {
                            @Override public void onComplete(AsyncEvent event) { lifecycle.complete(null); }
                            @Override public void onTimeout(AsyncEvent event) {}
                            @Override public void onError(AsyncEvent event) {}
                            @Override public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                        });
                        return context;
                    }
                }, response);
            });
            registration.setAsyncSupported(true);
            return registration;
        }
    }

    /** Wait for real container completion or recycling, including reset during emitter initialization. */
    static class ContainerCompletions {
        final ConcurrentMap<String, CompletableFuture<Void>> requests = new ConcurrentHashMap<>();
        String expect() {
            String id = UUID.randomUUID().toString();
            requests.put(id, new CompletableFuture<>());
            return id;
        }
        void awaitAll() throws Exception {
            CompletableFuture.allOf(requests.values().toArray(CompletableFuture[]::new)).get(WAIT_SECONDS, TimeUnit.SECONDS);
            requests.clear();
        }
    }

    @MockBean ChatService chats;
    @MockBean CurrentUser users;
    @MockBean ResumeDiagnosisService diagnoses;
    @MockBean ResumeOptimizationService optimizations;
    @Autowired ContainerCompletions completions;
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newHttpClient();
    static final List<String> PATHS = List.of("/api/v1/agent/chat",
            "/api/v1/resumes/resume/versions/version/analyze",
            "/api/v1/resumes/resume/versions/version/optimize");
    static final String BODY = "{\"content\":\"hello\",\"requestId\":\"request-1234567890\",\"jobDescription\":\"Java\"}";

    @BeforeEach void stubs() {
        when(users.require(any())).thenReturn("owner");
        when(diagnoses.begin(any(), any(), any(), any())).thenReturn(
                new ResumeDiagnosisService.Work("diag_test", "owner", "resume", null, null, null));
        when(optimizations.begin(any(), any(), any(), any())).thenReturn(
                new ResumeOptimizationService.Work("opt_test", "owner", "resume", null, null, "Java", null));
    }

    @AfterEach void awaitContainerCompletion(CapturedOutput output) throws Exception {
        // Upstream cancellation and HTTP EOF can precede Tomcat's final async dispatch.
        completions.awaitAll();
        assertThat(output.getAll()).doesNotContain("NullPointerException", "Unhandled exception", "onErrorDropped");
    }

    private AgentEvent started() {
        return new AgentEvent.RunStarted("run.started", 1, "run_test", Instant.now(), "conv_test", "msg_test", "stub", AgentMode.AUTO);
    }
    private AgentEvent completed() {
        return new AgentEvent.RunCompleted("run.completed", 2, "run_test", Instant.now(), "msg_test", "COMPLETED", 1);
    }

    @Test void concurrentCompletionWithFastHeartbeatsAlwaysEndsCleanly() throws Exception {
        Sinks.One<JsonNode> finish = Sinks.one();
        var subscribed = new CountDownLatch(CONCURRENT_REQUESTS);
        when(chats.chat(any(), any())).thenAnswer(call -> Flux.just(started())
                .concatWith(finish.asMono().map(ignored -> completed())).doOnSubscribe(ignored -> subscribed.countDown()));
        var report = finish.asMono().doOnSubscribe(ignored -> subscribed.countDown());
        when(diagnoses.execute(any())).thenReturn(report);
        when(optimizations.execute(any())).thenReturn(report);
        var heartbeats = new CountDownLatch(CONCURRENT_REQUESTS);
        var pending = streams(info -> new ObservedBody(":ping\n\n", heartbeats::countDown));
        try {
            assertThat(subscribed.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("all model publishers subscribed").isTrue();
            assertThat(heartbeats.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("every HTTP client received a heartbeat").isTrue();
            assertThat(finish.tryEmitValue(new ObjectMapper().createObjectNode())).isEqualTo(Sinks.EmitResult.OK);
            assertStreamsComplete(pending, true);
        } finally {
            finish.tryEmitEmpty();
            pending.forEach(future -> future.cancel(true));
        }
    }

    @Test void immediatePublishersAlwaysEndCleanly() throws Exception {
        when(chats.chat(any(), any())).thenReturn(Flux.just(started(), completed()));
        var report = Mono.<JsonNode>just(new ObjectMapper().createObjectNode());
        when(diagnoses.execute(any())).thenReturn(report);
        when(optimizations.execute(any())).thenReturn(report);
        assertStreamsComplete(streams(HttpResponse.BodyHandlers.ofString()), false);
    }

    private List<CompletableFuture<HttpResponse<String>>> streams(HttpResponse.BodyHandler<String> handler) {
        var pending = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) pending.add(client.sendAsync(request(PATHS.get(i % 3)), handler));
        return pending;
    }
    private void assertStreamsComplete(List<CompletableFuture<HttpResponse<String>>> pending, boolean expectHeartbeat) throws Exception {
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(WAIT_SECONDS, TimeUnit.SECONDS);
        for (var future : pending) {
            var response = future.join();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("event:run.started", "event:run.completed").endsWith("\n\n");
            assertThat(response.body().substring(response.body().lastIndexOf("event:run.completed"))).doesNotContain(":ping");
            if (expectHeartbeat) assertThat(response.body()).contains(":ping");
        }
    }

    @Test void midStreamFailureIsAnErrorEventAndNormalEof() throws Exception {
        var failures = List.of(Sinks.<JsonNode>one(), Sinks.<JsonNode>one(), Sinks.<JsonNode>one());
        when(chats.chat(any(), any())).thenReturn(Flux.just(started()).concatWith(failures.get(0).asMono().map(ignored -> completed())));
        when(diagnoses.execute(any())).thenReturn(failures.get(1).asMono());
        when(optimizations.execute(any())).thenReturn(failures.get(2).asMono());
        for (int i = 0; i < PATHS.size(); i++) {
            var receivedStarted = new CountDownLatch(1);
            var pending = client.sendAsync(request(PATHS.get(i)), info -> new ObservedBody("event:run.started", receivedStarted::countDown));
            try {
                assertThat(receivedStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("started frame received before failure").isTrue();
                assertThat(failures.get(i).tryEmitError(new BusinessException(ErrorCode.MODEL_UNAVAILABLE))).isEqualTo(Sinks.EmitResult.OK);
                var response = pending.get(WAIT_SECONDS, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
                assertThat(response.body()).contains("event:run.started", "event:error", "\"recoverable\":false", "\"code\":40010")
                        .doesNotContain("traceId", "event:run.completed").endsWith("\n\n");
            } finally {
                failures.get(i).tryEmitEmpty();
                pending.cancel(true);
            }
        }
    }

    @Test void clientDisconnectDisposesTheActiveModelSubscription() throws Exception {
        var subscribed = List.of(new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
        var cancelled = List.of(new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
        var domainCancelled = List.of(new CountDownLatch(1), new CountDownLatch(1));
        doAnswer(call -> { domainCancelled.get(0).countDown(); return null; }).when(diagnoses).cancel("owner", "diag_test");
        doAnswer(call -> { domainCancelled.get(1).countDown(); return null; }).when(optimizations).cancel("owner", "opt_test");
        // Configure each publisher once, before any callback can invoke a shared mock.
        // The model stays active until disconnect; heartbeat writes detect the broken socket.
        when(chats.chat(any(), any())).thenReturn(Flux.just(started()).concatWith(Flux.never())
                .doOnSubscribe(ignored -> subscribed.get(0).countDown()).doOnCancel(cancelled.get(0)::countDown));
        when(diagnoses.execute(any())).thenReturn(Mono.<JsonNode>never()
                .doOnSubscribe(ignored -> subscribed.get(1).countDown()).doOnCancel(cancelled.get(1)::countDown));
        when(optimizations.execute(any())).thenReturn(Mono.<JsonNode>never()
                .doOnSubscribe(ignored -> subscribed.get(2).countDown()).doOnCancel(cancelled.get(2)::countDown));
        for (int i = 0; i < PATHS.size(); i++) {
            String path = PATHS.get(i);
            // A real TCP reset avoids HttpClient pooling/buffer-draining semantics of InputStream.close().
            try (var socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(WAIT_SECONDS * 1000);
                socket.setSoLinger(true, 0);
                byte[] body = body(path).getBytes(StandardCharsets.UTF_8);
                String id = completions.expect();
                String headers = "POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                        + "\r\nContent-Type: application/json\r\nAccept: text/event-stream\r\n"
                        + REQUEST_ID_HEADER + ": " + id + "\r\nContent-Length: " + body.length + "\r\n\r\n";
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                assertThat(reader.readLine()).startsWith("HTTP/1.1 200");
                String line;
                do { line = reader.readLine(); assertThat(line).as("started frame before disconnect").isNotNull(); }
                while (!line.equals("event:run.started"));
                assertThat(subscribed.get(i).await(WAIT_SECONDS, TimeUnit.SECONDS)).as(path + " subscribed").isTrue();
                assertThat(cancelled.get(i).getCount()).as("publisher still active before TCP reset").isEqualTo(1);
            }
            assertThat(cancelled.get(i).await(WAIT_SECONDS, TimeUnit.SECONDS)).as(path + " disconnect must cancel publisher").isTrue();
            if (i > 0) assertThat(domainCancelled.get(i - 1).await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as(path + " disconnect must cancel domain work").isTrue();
            completions.awaitAll();
        }
        verify(diagnoses).cancel("owner", "diag_test");
        verify(optimizations).cancel("owner", "opt_test");
    }

    @Test void chatReconnectsWithLastEventIdAfterTcpResetWithoutCancellingTheRun() throws Exception {
        Sinks.Many<AgentEvent> model = Sinks.many().unicast().onBackpressureBuffer();
        var cancelled = new CountDownLatch(1);
        when(chats.chat(any(), any())).thenReturn(Flux.just(started()).concatWith(model.asFlux()).doOnCancel(cancelled::countDown));
        String path = "/api/v1/agent/chat";
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(WAIT_SECONDS * 1000);
            socket.setSoLinger(true, 0);
            byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
            String id = completions.expect();
            socket.getOutputStream().write(("POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nContent-Type: application/json\r\nAccept: text/event-stream\r\n"
                    + REQUEST_ID_HEADER + ": " + id + "\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            do { line = reader.readLine(); assertThat(line).isNotNull(); } while (!line.equals("event:run.started"));
        }
        // Event 2 is produced while no client is connected.
        model.tryEmitNext(new AgentEvent.TextDelta("text.delta", 2, "run_test", Instant.now(), null, "missed"));
        var resumed = client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/agent/runs/run_test/events"))
                .timeout(Duration.ofSeconds(WAIT_SECONDS)).header(REQUEST_ID_HEADER, completions.expect())
                .header("Accept", "text/event-stream").header("Last-Event-ID", "1").GET().build(), HttpResponse.BodyHandlers.ofString());
        model.tryEmitNext(new AgentEvent.TextDelta("text.delta", 3, "run_test", Instant.now(), null, "live"));
        model.tryEmitNext(new AgentEvent.RunCompleted("run.completed", 4, "run_test", Instant.now(), "msg_test", "COMPLETED", 1));
        var response = resumed.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("id:2", "missed", "id:3", "live", "event:run.completed").doesNotContain("event:run.started");
        assertThat(cancelled.getCount()).as("reconnect within grace keeps the run").isEqualTo(1);
        completions.awaitAll();
        // Unknown or expired runs are an ordinary 404 before any SSE frame (not tracked: no async lifecycle).
        assertThat(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/agent/runs/run_missing/events"))
                .header(REQUEST_ID_HEADER, "untracked").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
    }

    /** Observe chunk boundaries without blocking the HTTP executor or replacing its UTF-8 body decoder. */
    static class ObservedBody implements HttpResponse.BodySubscriber<String> {
        final HttpResponse.BodySubscriber<String> delegate = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        final String marker;
        final Runnable observed;
        String tail = "";
        boolean seen;
        ObservedBody(String marker, Runnable observed) { this.marker = marker; this.observed = observed; }
        @Override public CompletionStage<String> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) { delegate.onSubscribe(subscription); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (seen) break;
                String text = tail + StandardCharsets.UTF_8.decode(buffer.asReadOnlyBuffer());
                if (text.contains(marker)) { seen = true; observed.run(); }
                tail = text.substring(Math.max(0, text.length() - marker.length() + 1));
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { delegate.onError(error); }
        @Override public void onComplete() { delegate.onComplete(); }
    }

    private String body(String path) {
        return path.endsWith("optimize") ? "{\"requestId\":\"request-1234567890\",\"jobDescription\":\"Java\"}" : BODY;
    }
    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(WAIT_SECONDS))
                .header(REQUEST_ID_HEADER, completions.expect())
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body(path))).build();
    }
}
