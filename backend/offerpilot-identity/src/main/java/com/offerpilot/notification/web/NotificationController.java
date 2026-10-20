package com.offerpilot.notification.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.notification.application.Notifications;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    @ModelAttribute
    void preventCaching(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    private final Notifications notices; private final CurrentUser users;
    public NotificationController(Notifications notices, CurrentUser users) { this.notices=notices; this.users=users; }
    private String owner(HttpServletRequest request) { return users.require(request.getHeader("Authorization")); }
    @GetMapping
    public ApiResponse<Notifications.Page> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request) {
        return ApiResponse.ok(notices.list(owner(request),page,size),TraceIdFilter.current(request));
    }
    @GetMapping("/unread-count")
    public ApiResponse<Long> count(HttpServletRequest request) { return ApiResponse.ok(notices.unread(owner(request)),TraceIdFilter.current(request)); }
    @PutMapping("/{id}/read")
    public ApiResponse<Void> read(@PathVariable String id,HttpServletRequest request) {
        notices.read(owner(request),id); return ApiResponse.ok(null,TraceIdFilter.current(request));
    }
    @PutMapping("/read-all")
    public ApiResponse<Void> all(HttpServletRequest request) {
        notices.readAll(owner(request)); return ApiResponse.ok(null,TraceIdFilter.current(request));
    }
}
