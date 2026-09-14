package com.offerpilot.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.application.ChatService;
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

    public AgentController(ObjectMapper objectMapper, ChatService chatService) {
        this.objectMapper = objectMapper;
        this.chatService = chatService;
    }

    /**
     * HTTP/SSE 适配器：业务事件由应用服务生成，断开或超时后释放模型订阅。
     */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request, HttpServletResponse response) {
        var events = chatService.chat(request);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setCharacterEncoding("UTF-8");
        SseEmitter emitter = new SseEmitter(10 * 60_000L);
        var subscription = Disposables.swap();
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(ignored -> subscription.dispose());
        subscription.update(events.publishOn(Schedulers.boundedElastic())
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
}
