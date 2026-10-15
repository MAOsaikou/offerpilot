package com.offerpilot.agent.web;

import com.offerpilot.common.web.SseStreams;
import com.offerpilot.agent.event.AgentEvent;
import com.offerpilot.agent.application.ChatService;
import com.offerpilot.agent.application.RunRelay;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Flux;
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
    private final RunRelay relay;

    public AgentController(SseStreams streams, ChatService chatService, CurrentUser users, RunRelay relay) {
        this.streams = streams;
        this.chatService = chatService;
        this.users = users;
        this.relay = relay;
    }

    /**
     * HTTP/SSE 适配器：业务事件由应用服务生成。运行与连接解耦（AG-07）：断开后在宽限期内可用
     * {@code GET /runs/{id}/events} 续传，无人重连才释放模型订阅。
     */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request, HttpServletRequest http, HttpServletResponse response) {
        String owner = users.require(http.getHeader("Authorization"));
        return stream(relay.start(owner, chatService.chat(owner, request)), response);
    }

    /** 断线续传：回放 Last-Event-ID（或 after 参数）之后的全部事件，再接上实时事件。 */
    @GetMapping(path = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             @RequestParam(value = "after", required = false) Integer after,
                             HttpServletRequest http, HttpServletResponse response) {
        int from = after != null ? after : parse(lastEventId);
        return stream(relay.resume(users.require(http.getHeader("Authorization")), id, from), response);
    }

    private static int parse(String lastEventId) {
        try { return lastEventId == null ? 0 : Integer.parseInt(lastEventId.trim()); }
        catch (NumberFormatException ex) { return 0; }
    }

    private SseEmitter stream(Flux<AgentEvent> events, HttpServletResponse response) {
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
