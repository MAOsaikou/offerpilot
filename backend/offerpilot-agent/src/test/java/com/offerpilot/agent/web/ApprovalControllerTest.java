package com.offerpilot.agent.web;

import com.offerpilot.agent.application.ApprovalService;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApprovalControllerTest {
    @Test void bindsDecisionAndOwnerAndReturnsValidationNotFoundAndConflict() throws Exception {
        var approvals = mock(ApprovalService.class);
        var users = mock(CurrentUser.class);
        when(users.require("Bearer trusted")).thenReturn("alice");
        var mvc = MockMvcBuilders.standaloneSetup(new ApprovalController(approvals, users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var decision = new ApprovalService.Decision("APPROVE", Map.of("status", "INTERVIEW"), null);
        when(approvals.resolve("alice", "run1", "a1", decision)).thenReturn(decision);
        String path = "/api/v1/agent/runs/run1/approvals/a1";
        mvc.perform(post(path).header("Authorization", "Bearer trusted").contentType("application/json")
                .content("{\"decision\":\"APPROVE\",\"args\":{\"status\":\"INTERVIEW\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.decision").value("APPROVE"));
        verify(approvals).resolve("alice", "run1", "a1", decision);
        for (ErrorCode code : java.util.List.of(ErrorCode.RUN_NOT_FOUND, ErrorCode.APPROVAL_EXPIRED, ErrorCode.VALIDATION_FAILED)) {
            doThrow(new BusinessException(code)).when(approvals).resolve(any(), any(), any(), any());
            mvc.perform(post(path).header("Authorization", "Bearer trusted").contentType("application/json").content("{\"decision\":\"REJECT\"}"))
                    .andExpect(status().is(code.status().value()));
        }
        mvc.perform(post(path).contentType("application/json").content("bad json")).andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType("application/json").content("null")).andExpect(status().isBadRequest());
    }
}
