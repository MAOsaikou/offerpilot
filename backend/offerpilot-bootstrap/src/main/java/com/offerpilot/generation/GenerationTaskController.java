package com.offerpilot.generation;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/generation-tasks")
public class GenerationTaskController {
    private final GenerationTaskService tasks;
    private final CurrentUser users;

    public GenerationTaskController(GenerationTaskService tasks, CurrentUser users) {
        this.tasks = tasks;
        this.users = users;
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(required=false) String kind, @RequestParam(required=false) String entityId, HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        var all=new java.util.ArrayList<Object>(tasks.recent(owner,kind,entityId));
        if (kind==null && entityId==null) all.addAll(tasks.listRunning(owner).stream()
                .filter(legacy -> all.stream().noneMatch(t -> t instanceof GenerationTaskService.BackgroundTask b && legacy.id().equals(b.resultId()))).toList());
        return ApiResponse.ok(all, TraceIdFilter.current(request));
    }

    @PostMapping
    public ApiResponse<?> start(@RequestBody GenerationTaskService.Start body, HttpServletRequest request) {
        return ApiResponse.ok(tasks.start(users.require(request.getHeader("Authorization")),body),TraceIdFilter.current(request));
    }
    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(tasks.get(users.require(request.getHeader("Authorization")),id),TraceIdFilter.current(request));
    }
    @GetMapping("/{id}/result")
    public ApiResponse<?> result(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(tasks.result(users.require(request.getHeader("Authorization")),id),TraceIdFilter.current(request));
    }
    @PostMapping("/{id}/cancel")
    public ApiResponse<?> cancelBackground(@PathVariable String id, HttpServletRequest request) {
        tasks.cancelBackground(users.require(request.getHeader("Authorization")),id);
        return ApiResponse.ok(null,TraceIdFilter.current(request));
    }
    @PostMapping("/{kind}/{id}/cancel")
    public ApiResponse<?> cancel(@PathVariable String kind, @PathVariable String id, HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        tasks.cancel(owner, kind, id);
        return ApiResponse.ok(null, TraceIdFilter.current(request));
    }
}
