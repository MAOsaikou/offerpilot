package com.offerpilot.ops;

import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/ops")
public class OpsController {
    private final PasswordLoginService logins;
    private final OpsMonitor monitor;
    private final JdbcTemplate jdbc;
    public OpsController(PasswordLoginService logins,OpsMonitor monitor,JdbcTemplate jdbc) {
        this.logins=logins;this.monitor=monitor;this.jdbc=jdbc;
    }
    @GetMapping("/status")
    public ApiResponse<OpsMonitor.Status> status(@RequestHeader(value="Authorization",required=false) String authorization,HttpServletRequest req,HttpServletResponse res) {
        logins.requireStaffId(authorization);res.setHeader("Cache-Control","no-store");
        return ApiResponse.ok(monitor.status(),TraceIdFilter.current(req));
    }
    @GetMapping("/cleanup")
    public ApiResponse<List<Map<String,Object>>> cleanup(@RequestHeader(value="Authorization",required=false) String authorization,HttpServletRequest req,HttpServletResponse res) {
        logins.requireStaffId(authorization);res.setHeader("Cache-Control","no-store");
        // Bounded pending receipts. Object keys, buckets and deleted identities are never returned.
        return ApiResponse.ok(jdbc.queryForList("SELECT id,kind,attempts,next_attempt_at,created_at FROM account_data_cleanup ORDER BY created_at LIMIT 100"),TraceIdFilter.current(req));
    }
}
