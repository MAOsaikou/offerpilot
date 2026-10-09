package com.offerpilot;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Real Tomcat and HTTP, stub application/model publishers; no network providers or databases. */
@SpringBootTest(classes = SseStreamHttpIntegrationTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "offerpilot.sse.heartbeat-interval=2ms")
@ExtendWith(OutputCaptureExtension.class)
class SseStreamHttpIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class,
            org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class})
    @Import({AgentController.class, ResumeDiagnosisController.class, ResumeOptimizationController.class,
            JacksonConfig.class, GlobalExceptionHandler.class})
    static class Application {}

    @MockBean ChatService chats;
    @MockBean CurrentUser users;
    @MockBean ResumeDiagnosisService diagnoses;
    @MockBean ResumeOptimizationService optimizations;
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

    private AgentEvent started() {
        return new AgentEvent.RunStarted("run.started", 1, "run_test", Instant.now(), "conv_test", "msg_test", "stub", AgentMode.AUTO);
    }

    private AgentEvent completed() {
        return new AgentEvent.RunCompleted("run.completed", 2, "run_test", Instant.now(), "msg_test", "COMPLETED", 1);
    }

    @Test void concurrentCompletionWithFastHeartbeatsAlwaysEndsCleanly(CapturedOutput output) throws Exception {
        when(chats.chat(any(), any())).thenAnswer(call -> Flux.just(started(), completed())
                .delayElements(Duration.ofMillis(6)));
        when(diagnoses.execute(any())).thenReturn(Mono.<com.fasterxml.jackson.databind.JsonNode>just(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())
                .delayElement(Duration.ofMillis(12)));
        when(optimizations.execute(any())).thenReturn(Mono.<com.fasterxml.jackson.databind.JsonNode>just(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())
                .delayElement(Duration.ofMillis(12)));
        var pending = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
        for (int i = 0; i < 96; i++) pending.add(client.sendAsync(request(PATHS.get(i % 3)), HttpResponse.BodyHandlers.ofString()));
        for (var future : pending) {
            var response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("event:run.started", "event:run.completed").endsWith("\n\n");
            assertThat(response.body().substring(response.body().lastIndexOf("event:run.completed"))).doesNotContain(":ping");
        }
        assertThat(output.getAll()).doesNotContain("NullPointerException", "Unhandled exception", "onErrorDropped");
    }

    @Test void midStreamFailureIsAnErrorEventAndNormalEof(CapturedOutput output) throws Exception {
        var error = new BusinessException(ErrorCode.MODEL_UNAVAILABLE);
        when(chats.chat(any(), any())).thenReturn(Flux.just(started()).concatWith(
                Mono.delay(Duration.ofMillis(30)).flatMapMany(tick -> Flux.error(error))));
        when(diagnoses.execute(any())).thenReturn(Mono.delay(Duration.ofMillis(30)).flatMap(tick -> Mono.error(error)));
        when(optimizations.execute(any())).thenReturn(Mono.delay(Duration.ofMillis(30)).flatMap(tick -> Mono.error(error)));
        for (String path : PATHS) {
            var response = client.send(request(path), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
            assertThat(response.body()).contains("event:run.started", "event:error", "\"recoverable\":false", "\"code\":40010")
                    .doesNotContain("traceId", "event:run.completed").endsWith("\n\n");
        }
        assertThat(output.getAll()).doesNotContain("NullPointerException", "Unhandled exception", "onErrorDropped");
    }

    @Test void clientDisconnectDisposesTheActiveModelSubscription() throws Exception {
        var cancelled = new CountDownLatch(1);
        when(chats.chat(any(), any())).thenReturn(Flux.just(started()).concatWith(
                Flux.interval(Duration.ofMillis(5)).map(tick -> (AgentEvent)new AgentEvent.TextDelta(
                        "text.delta", tick.intValue() + 2, "run_test", Instant.now(), null, "x".repeat(8192))))
                .doOnCancel(cancelled::countDown));
        var response = client.send(request(PATHS.get(0)), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        try (var body = response.body()) { assertThat(body.read()).isNotEqualTo(-1); }
        assertThat(cancelled.await(5, TimeUnit.SECONDS)).as("disconnect must cancel model publisher").isTrue();
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(path.endsWith("optimize")
                        ? "{\"requestId\":\"request-1234567890\",\"jobDescription\":\"Java\"}" : BODY)).build();
    }
}
