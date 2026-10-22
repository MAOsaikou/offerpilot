package com.offerpilot.knowledge.web;

import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.knowledge.application.RetrievalDebug;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/knowledge/retrieval-debug")
public class RetrievalDebugController {
    private final PasswordLoginService staff;
    private final RetrievalDebug debug;
    public RetrievalDebugController(PasswordLoginService staff, RetrievalDebug debug) { this.staff=staff;this.debug=debug; }
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> invalidQuery(HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.badRequest().body(ApiResponse.fail(
                com.offerpilot.common.error.ErrorCode.VALIDATION_FAILED.code(),
                "query 为必填文字，limit 必须为整数 1-50", TraceIdFilter.current(request)));
    }
    @GetMapping
    public ApiResponse<RetrievalDebug.Result> search(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestParam String query, @RequestParam(defaultValue="10") int limit, HttpServletRequest request, HttpServletResponse response) {
        staff.requireStaffId(auth);
        response.setHeader("Cache-Control","no-store");
        return ApiResponse.ok(debug.search(query,limit), TraceIdFilter.current(request));
    }
}
