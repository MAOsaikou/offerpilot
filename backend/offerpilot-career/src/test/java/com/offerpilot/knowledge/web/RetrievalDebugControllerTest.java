package com.offerpilot.knowledge.web;

import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.common.error.*;
import com.offerpilot.knowledge.application.RetrievalDebug;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RetrievalDebugControllerTest {
    @Test void missingGuestAndOrdinaryCredentialsCannotReachRetrieval() throws Exception {
        var staff=mock(PasswordLoginService.class);var debug=mock(RetrievalDebug.class);
        var mvc=MockMvcBuilders.standaloneSetup(new RetrievalDebugController(staff,debug)).setControllerAdvice(new GlobalExceptionHandler()).build();
        when(staff.requireStaffId(null)).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));
        when(staff.requireStaffId("Bearer ordinary")).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        when(staff.requireStaffId("Bearer guest")).thenThrow(new BusinessException(ErrorCode.TOKEN_INVALID));
        mvc.perform(get("/api/v1/staff/knowledge/retrieval-debug").param("query","Java")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/staff/knowledge/retrieval-debug").header("Authorization","Bearer ordinary").param("query","Java")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/staff/knowledge/retrieval-debug").header("Authorization","Bearer guest").param("query","Java")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/staff/knowledge/retrieval-debug").header("Authorization","Bearer staff").param("query","Java").param("limit","abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10001));
        mvc.perform(get("/api/v1/staff/knowledge/retrieval-debug").header("Authorization","Bearer staff"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10001));
        verifyNoInteractions(debug);
        when(staff.requireStaffId("Bearer staff")).thenReturn("staff-owner");
        when(debug.search("Java",10)).thenReturn(new RetrievalDebug.Result(List.of(),false,"local","coverage","FTS only"));
        mvc.perform(get("/api/v1/staff/knowledge/retrieval-debug").header("Authorization","Bearer staff").param("query","Java"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.vectorAvailable").value(false));
        when(debug.search("Java",10,"staff_docs")).thenReturn(new RetrievalDebug.Result(List.of(),false,"local","coverage","staff FTS"));
        mvc.perform(get("/api/v1/staff/knowledge/retrieval-debug").header("Authorization","Bearer staff")
                .param("query","Java").param("collection","staff_docs"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.notice").value("staff FTS"));
    }
}
