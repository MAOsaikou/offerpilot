package com.offerpilot.conversation.web;

import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.conversation.application.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService conversations;
    private final CurrentUser users;
    public ConversationController(ConversationService conversations, CurrentUser users) { this.conversations = conversations; this.users = users; }
    public record Create(@Size(max=100) @Pattern(regexp="(?s).*\\S.*") String title, AgentMode mode) {}
    public record Update(@Size(max=100) @Pattern(regexp="(?s).*\\S.*") String title, Boolean pinned) {}
    private String owner(HttpServletRequest r) { return users.require(r.getHeader("Authorization")); }
    private <T> ApiResponse<T> ok(T value, HttpServletRequest r) { return ApiResponse.ok(value, TraceIdFilter.current(r)); }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size, HttpServletRequest r) {
        return ok(conversations.list(owner(r), page, size), r);
    }
    @PostMapping
    public ApiResponse<?> create(@Valid @RequestBody Create body, HttpServletRequest r) {
        return ok(conversations.create(owner(r), body.title(), body.mode()), r);
    }
    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable String id, HttpServletRequest r) { return ok(conversations.get(owner(r), id), r); }
    @PatchMapping("/{id}")
    public ApiResponse<?> update(@PathVariable String id, @Valid @RequestBody Update body, HttpServletRequest r) {
        return ok(conversations.update(owner(r), id, body.title(), body.pinned()), r);
    }
    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable String id, HttpServletRequest r) { conversations.delete(owner(r), id); return ok(null, r); }
    @GetMapping("/{id}/messages")
    public ApiResponse<?> messages(@PathVariable String id, @RequestParam(required=false) String before,
                                  @RequestParam(defaultValue="30") int size, HttpServletRequest r) {
        return ok(conversations.messages(owner(r), id, before, size), r);
    }
}
