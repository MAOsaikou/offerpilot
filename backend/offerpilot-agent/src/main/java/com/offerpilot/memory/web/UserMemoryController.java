package com.offerpilot.memory.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.memory.application.UserMemoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/memories")
public class UserMemoryController {
    public record Selection(@NotNull Boolean memoryEnabled) {}
    private final CurrentUser users;
    private final UserMemoryService memories;
    public UserMemoryController(CurrentUser users, UserMemoryService memories) { this.users = users; this.memories = memories; }
    @GetMapping public ApiResponse<?> list(HttpServletRequest request) {
        return ApiResponse.ok(memories.list(users.require(request.getHeader("Authorization"))), TraceIdFilter.current(request));
    }
    @PutMapping("/settings") public ApiResponse<?> settings(@Valid @RequestBody Selection selection, HttpServletRequest request) {
        return ApiResponse.ok(memories.settings(users.require(request.getHeader("Authorization")), selection.memoryEnabled()), TraceIdFilter.current(request));
    }
    @PostMapping public ApiResponse<?> add(@RequestBody UserMemoryService.Draft draft, HttpServletRequest request) {
        return ApiResponse.ok(memories.add(users.require(request.getHeader("Authorization")), draft), TraceIdFilter.current(request));
    }
    @PatchMapping("/{id}") public ApiResponse<?> edit(@PathVariable String id, @RequestBody UserMemoryService.Draft draft, HttpServletRequest request) {
        return ApiResponse.ok(memories.edit(users.require(request.getHeader("Authorization")), id, draft), TraceIdFilter.current(request));
    }
    @DeleteMapping("/{id}") public ApiResponse<?> delete(@PathVariable String id, HttpServletRequest request) {
        memories.delete(users.require(request.getHeader("Authorization")), id);
        return ApiResponse.ok(null, TraceIdFilter.current(request));
    }
    @DeleteMapping public ApiResponse<?> clear(HttpServletRequest request) {
        memories.clear(users.require(request.getHeader("Authorization")));
        return ApiResponse.ok(null, TraceIdFilter.current(request));
    }
}
