package com.offerpilot.resume.web;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.common.web.SseStreams;
import com.offerpilot.resume.application.ResumeOptimizationService;
import jakarta.servlet.http.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;

@RestController
@RequestMapping("/api/v1/resumes")
public class ResumeOptimizationController {
    private final ResumeOptimizationService optimizations;
    private final CurrentUser users;
    private final SseStreams streams;

    public ResumeOptimizationController(ResumeOptimizationService optimizations, CurrentUser users, SseStreams streams) {
        this.optimizations = optimizations;
        this.users = users;
        this.streams = streams;
    }

    @PostMapping(path = "/{id}/versions/{vid}/optimize", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter optimize(@PathVariable String id, @PathVariable String vid, @RequestBody JsonNode body,
                              HttpServletRequest request, HttpServletResponse response) {
        var work = optimizations.begin(owner(request), id, vid, ResumeOptimizationService.input(body));
        var writer = streams.open(response, 110000L, work.id(), "改写暂时失败，请重试", () -> {
            if (work.completed() == null) optimizations.cancel(work.owner(), work.id());
        });
        writer.send("run.started", Map.of("reportId", work.id(), "versionId", vid));
        writer.subscribe(reactor.core.publisher.Mono.defer(() -> optimizations.execute(work)), report -> {
            writer.send("ui.card", Map.of("cardId", work.id(), "cardType", "resume_diff", "payload", report));
            writer.send("run.completed", Map.of("status", "COMPLETED"));
        });
        return writer.emitter();
    }

    @GetMapping("/{id}/optimizations")
    public ApiResponse<?> list(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(optimizations.list(owner(request), id), TraceIdFilter.current(request));
    }

    @GetMapping("/optimizations/{id}")
    public ApiResponse<?> get(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(optimizations.get(owner(request), id), TraceIdFilter.current(request));
    }

    @PostMapping("/optimizations/{id}/cancel")
    public ApiResponse<?> cancel(@PathVariable String id, HttpServletRequest request) {
        optimizations.cancel(owner(request), id);
        return ApiResponse.ok(null, TraceIdFilter.current(request));
    }

    private String owner(HttpServletRequest request) {
        return users.require(request.getHeader("Authorization"));
    }

}
