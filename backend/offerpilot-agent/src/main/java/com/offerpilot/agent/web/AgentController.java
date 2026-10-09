package com.offerpilot.agent.web;

import com.offerpilot.common.web.SseStreams;
import com.offerpilot.agent.event.AgentEvent;
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

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final SseStreams streams;
    private final ChatService chatService;
    private final CurrentUser users;

    public AgentController(SseStreams streams, ChatService chatService, CurrentUser users) {
        this.streams = streams;
        this.chatService = chatService;
        this.users = users;
    }

    /**
     * HTTP/SSE 适配器：业务事件由应用服务生成，断开或超时后释放模型订阅。
     */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request, HttpServletRequest http, HttpServletResponse response) {
        var events = chatService.chat(users.require(http.getHeader("Authorization")), request);
        var writer = streams.open(response, 100 * 60_000L, "run_stream", "回复暂时失败，请重试", () -> {});
        writer.subscribe(events, event -> writer.send(event.id(), event.type(), event.runId(), event,
                event instanceof AgentEvent.RunCompleted
                        || event instanceof AgentEvent.ErrorEvent error && !error.recoverable()));
        return writer.emitter();
    }

    @PostMapping("/runs/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String id, HttpServletRequest request) {
        chatService.cancel(users.require(request.getHeader("Authorization")), id);
        return ApiResponse.ok(null, com.offerpilot.common.web.TraceIdFilter.current(request));
    }
}
