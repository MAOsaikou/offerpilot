package com.offerpilot.resume.web;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.resume.application.ResumeOptimizationService;
import jakarta.servlet.http.*;
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
public class ResumeOptimizationController {
    private final ResumeOptimizationService optimizations;
    private final CurrentUser users;
    private final ObjectMapper json;
    public ResumeOptimizationController(ResumeOptimizationService optimizations, CurrentUser users, ObjectMapper json) {
        this.optimizations=optimizations; this.users=users; this.json=json;
    }
    @PostMapping(path="/{id}/versions/{vid}/optimize",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter optimize(@PathVariable String id,@PathVariable String vid,@RequestBody JsonNode body,
            HttpServletRequest request,HttpServletResponse response) {
        var work=optimizations.begin(owner(request),id,vid,ResumeOptimizationService.input(body));
        response.setHeader("Cache-Control","no-cache"); response.setHeader("X-Accel-Buffering","no"); response.setCharacterEncoding("UTF-8");
        var emitter=new SseEmitter(110000L);
        var subscription=Disposables.swap(); var heartbeat=Disposables.swap();
        var resources=Disposables.composite(subscription,heartbeat);
        Runnable dispose=() -> { resources.dispose(); if(work.completed()==null) optimizations.cancel(work.owner(),work.id()); };
        emitter.onCompletion(dispose); emitter.onTimeout(dispose); emitter.onError(error -> dispose.run());
        AtomicInteger seq=new AtomicInteger();
        try {
            send(emitter,seq,work.id(),"run.started",Map.of("reportId",work.id(),"versionId",vid));
            heartbeat.update(Flux.interval(Duration.ofSeconds(15)).publishOn(Schedulers.boundedElastic()).subscribe(tick -> {
                try {emitter.send(SseEmitter.event().comment("ping"));} catch(Exception ex) {dispose.run();emitter.complete();}
            }));
            subscription.update(optimizations.execute(work).subscribe(report -> {
                try {
                    send(emitter,seq,work.id(),"ui.card",Map.of("cardId",work.id(),"cardType","resume_diff","payload",report));
                    send(emitter,seq,work.id(),"run.completed",Map.of("status","COMPLETED"));
                    emitter.complete();
                } catch(Exception ex) {emitter.completeWithError(ex);}
            },error -> {
                try {send(emitter,seq,work.id(),"error",Map.of("code",error instanceof BusinessException b?b.errorCode().code():50000,
                    "message",error instanceof BusinessException b?b.getMessage():"改写暂时失败，请重试","recoverable",false));}
                catch(Exception ignored) { /* The task can be restored through GET after a disconnect. */ }
                emitter.complete();
            }));
        } catch(Exception ex) {dispose.run();emitter.completeWithError(ex);}
        return emitter;
    }
    @GetMapping("/{id}/optimizations")
    public ApiResponse<?> list(@PathVariable String id,HttpServletRequest request) {return ApiResponse.ok(optimizations.list(owner(request),id),TraceIdFilter.current(request));}
    @GetMapping("/optimizations/{id}")
    public ApiResponse<?> get(@PathVariable String id,HttpServletRequest request) {return ApiResponse.ok(optimizations.get(owner(request),id),TraceIdFilter.current(request));}
    @PostMapping("/optimizations/{id}/cancel")
    public ApiResponse<?> cancel(@PathVariable String id,HttpServletRequest request) {optimizations.cancel(owner(request),id);return ApiResponse.ok(null,TraceIdFilter.current(request));}
    private String owner(HttpServletRequest request) {return users.require(request.getHeader("Authorization"));}
    private void send(SseEmitter emitter,AtomicInteger seq,String runId,String type,Map<String,Object> fields) throws java.io.IOException {
        var event=new LinkedHashMap<String,Object>(fields);event.put("type",type);event.put("id",seq.incrementAndGet());event.put("runId",runId);event.put("ts",Instant.now().toString());
        emitter.send(SseEmitter.event().id(String.valueOf(seq.get())).name(type).data(json.writeValueAsString(event),MediaType.APPLICATION_JSON));
    }
}
