package com.offerpilot.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.application.ChatService;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import reactor.core.Disposables;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final ObjectMapper objectMapper;
    private final ChatService chatService;
    private final CurrentUser users;

    public AgentController(ObjectMapper objectMapper, ChatService chatService, CurrentUser users) {
        this.objectMapper = objectMapper;
        this.chatService = chatService;
        this.users = users;
    }

    /**
     * HTTP/SSE 适配器：业务事件由应用服务生成，断开或超时后释放模型订阅。
     */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request, HttpServletRequest http, HttpServletResponse response) {
        var events = chatService.chat(users.require(http.getHeader("Authorization")), request);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setCharacterEncoding("UTF-8");
        SseEmitter emitter = new SseEmitter(10 * 60_000L);
        var subscription = Disposables.swap();
        var heartbeat = Disposables.swap();
        var resources = Disposables.composite(subscription, heartbeat);
        emitter.onCompletion(resources::dispose);
        emitter.onTimeout(resources::dispose);
        emitter.onError(ignored -> resources.dispose());
        heartbeat.update(reactor.core.publisher.Flux.interval(java.time.Duration.ofSeconds(15))
                .publishOn(Schedulers.boundedElastic()).subscribe(ignored -> {
                    try { emitter.send(SseEmitter.event().comment("ping")); }
                    catch (java.io.IOException ex) { resources.dispose(); emitter.complete(); }
                }));
        subscription.update(events.subscribeOn(Schedulers.boundedElastic()).publishOn(Schedulers.boundedElastic())
                .doOnNext(event -> {
                    try {
                        emitter.send(SseEmitter.event().id(String.valueOf(event.id())).name(event.type())
                                .data(objectMapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
                    } catch (java.io.IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                }).subscribe(ignored -> {}, emitter::completeWithError, emitter::complete));
        return emitter;
    }

    @PostMapping("/runs/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String id, HttpServletRequest request) {
        chatService.cancel(users.require(request.getHeader("Authorization")), id);
        return ApiResponse.ok(null, com.offerpilot.common.web.TraceIdFilter.current(request));
    }
}
