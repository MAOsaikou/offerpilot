package com.offerpilot.resume.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.common.web.SseStreams;
import com.offerpilot.resume.application.ResumeDiagnosisService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;

@RestController
@RequestMapping("/api/v1/resumes")
public class ResumeDiagnosisController {
    private final ResumeDiagnosisService diagnoses;
    private final CurrentUser users;
    private final SseStreams streams;

    public ResumeDiagnosisController(ResumeDiagnosisService diagnoses, CurrentUser users, SseStreams streams) {
        this.diagnoses = diagnoses;
        this.users = users;
        this.streams = streams;
    }

    public record Analyze(@NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{16,80}") String requestId) {}

    @PostMapping(path = "/{id}/versions/{vid}/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyze(@PathVariable String id, @PathVariable String vid, @Valid @RequestBody Analyze body,
                             HttpServletRequest request, HttpServletResponse response) {
        var work = diagnoses.begin(owner(request), id, vid, body.requestId());
        var writer = streams.open(response, 110000L, work.id(), "诊断暂时失败，请重试", () -> {
            if (work.completed() == null) diagnoses.cancel(work.owner(), work.id());
        });
        writer.send("run.started", Map.of("reportId", work.id(), "versionId", vid));
        writer.subscribe(reactor.core.publisher.Mono.defer(() -> diagnoses.execute(work)), report -> {
            writer.send("ui.card", Map.of("cardId", work.id(), "cardType", "resume_diagnosis", "payload", report));
            writer.send("run.completed", Map.of("status", "COMPLETED"));
        });
        return writer.emitter();
    }

    @GetMapping("/{id}/diagnoses")
    public ApiResponse<?> list(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(diagnoses.list(owner(request), id), TraceIdFilter.current(request));
    }

    @GetMapping("/diagnoses/{id}")
    public ApiResponse<?> get(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(diagnoses.get(owner(request), id), TraceIdFilter.current(request));
    }

    @PostMapping("/diagnoses/{id}/cancel")
    public ApiResponse<?> cancel(@PathVariable String id, HttpServletRequest request) {
        diagnoses.cancel(owner(request), id);
        return ApiResponse.ok(null, TraceIdFilter.current(request));
    }

    private String owner(HttpServletRequest request) {
        return users.require(request.getHeader("Authorization"));
    }

}
