package com.offerpilot.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountDataController {
    private final CurrentUser users;
    private final AccountDataDeletionService deletion;
    public AccountDataController(CurrentUser users, AccountDataDeletionService deletion) { this.users = users; this.deletion = deletion; }
    @DeleteMapping("/api/v1/users/me/data")
    public ApiResponse<Void> delete(@RequestHeader(value="Authorization", required=false) String authorization,
            @RequestBody(required=false) JsonNode body, HttpServletRequest request, HttpServletResponse response) {
        String owner = users.require(authorization);
        if (body == null || !body.isObject() || body.size()!=1 || !body.path("confirm").isTextual()
                || !"DELETE".equals(body.path("confirm").textValue()))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请输入 DELETE 确认永久删除账号和全部数据");
        deletion.delete(owner);
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(null, TraceIdFilter.current(request));
    }
}
