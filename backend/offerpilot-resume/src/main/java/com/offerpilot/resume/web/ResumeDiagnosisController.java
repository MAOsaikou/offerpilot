package com.offerpilot.resume.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.resume.application.ResumeDiagnosisService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/v1/resumes")
public class ResumeDiagnosisController {
    private final ResumeDiagnosisService diagnoses;
    private final CurrentUser users;
    private final ObjectMapper json;

    public ResumeDiagnosisController(ResumeDiagnosisService diagnoses, CurrentUser users, ObjectMapper json) {
        this.diagnoses = diagnoses;
        this.users = users;
        this.json = json;
    }

    public record Analyze(@NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{16,80}") String requestId) {}

    @PostMapping(path = "/{id}/versions/{vid}/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyze(@PathVariable String id, @PathVariable String vid, @Valid @RequestBody Analyze body,
                             HttpServletRequest request, HttpServletResponse response) {
        var work = diagnoses.begin(owner(request), id, vid, body.requestId());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setCharacterEncoding("UTF-8");
        var emitter = new SseEmitter(110000L);
        var subscription = Disposables.swap();
        var heartbeat = Disposables.swap();
        var resources = Disposables.composite(subscription, heartbeat);
        Runnable dispose = () -> {
            resources.dispose();
            if (work.completed() == null)
                diagnoses.cancel(work.owner(), work.id());
        };
        emitter.onCompletion(dispose);
        emitter.onTimeout(dispose);
        emitter.onError(error -> dispose.run());
        AtomicInteger seq = new AtomicInteger();
        try {
            send(emitter, seq, work.id(), "run.started", Map.of("reportId", work.id(), "versionId", vid));
            heartbeat.update(Flux.interval(Duration.ofSeconds(15)).publishOn(Schedulers.boundedElastic()).subscribe(tick -> {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception ex) {
                    dispose.run();
                    emitter.complete();
                }
            }));
            subscription.update(diagnoses.execute(work).subscribe(report -> {
                try {
                    send(emitter, seq, work.id(), "ui.card",
                            Map.of("cardId", work.id(), "cardType", "resume_diagnosis", "payload", report));
                    send(emitter, seq, work.id(), "run.completed", Map.of("status", "COMPLETED"));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            }, error -> {
                try {
                    send(emitter, seq, work.id(), "error",
                            Map.of("code", error instanceof BusinessException b ? b.errorCode().code() : 50000,
                                    "message", error instanceof BusinessException b ? b.getMessage() : "诊断暂时失败，请重试",
                                    "recoverable", false));
                } catch (Exception ignored) {
                    /* Disconnected clients restore status through GET. */
                }
                emitter.complete();
            }));
        } catch (Exception ex) {
            dispose.run();
            emitter.completeWithError(ex);
        }
        return emitter;
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

    private void send(SseEmitter emitter, AtomicInteger seq, String runId, String type, Map<String, Object> fields)
            throws java.io.IOException {
        var event = new LinkedHashMap<String, Object>(fields);
        event.put("type", type);
        event.put("id", seq.incrementAndGet());
        event.put("runId", runId);
        event.put("ts", Instant.now().toString());
        emitter.send(SseEmitter.event().id(String.valueOf(seq.get())).name(type)
                .data(json.writeValueAsString(event), MediaType.APPLICATION_JSON));
    }
}
