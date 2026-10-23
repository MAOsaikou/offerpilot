package com.offerpilot.knowledge.web;

import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.common.error.*;
import com.offerpilot.knowledge.application.StaffKnowledgeDocuments;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StaffKnowledgeControllerTest {
    @Test void everyOperationRequiresStaffAndUploadUsesOnlyVerifiedIdentity() throws Exception {
        var staff = mock(PasswordLoginService.class);
        var documents = mock(StaffKnowledgeDocuments.class);
        var mvc = MockMvcBuilders.standaloneSetup(new StaffKnowledgeController(staff, documents))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(staff.requireStaffId(null)).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));
        when(staff.requireStaffId("Bearer user")).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        when(staff.requireStaffId("Bearer guest")).thenThrow(new BusinessException(ErrorCode.TOKEN_INVALID));
        var file = new MockMultipartFile("file", "ops.txt", "text/plain", "Redis runbook".getBytes());
        for (String credential : new String[]{null,"Bearer user","Bearer guest"}) {
            int expected = "Bearer user".equals(credential) ? 403 : 401;
            var requests = java.util.List.of(get("/api/v1/staff/knowledge/documents"),
                    get("/api/v1/staff/knowledge/documents/doc"), delete("/api/v1/staff/knowledge/documents/doc"),
                    post("/api/v1/staff/knowledge/documents/doc/reindex"),
                    multipart("/api/v1/staff/knowledge/documents/upload").file(file));
            for (var request : requests) {
                if (credential != null) request.header("Authorization",credential);
                mvc.perform(request).andExpect(status().is(expected)).andExpect(header().string("Cache-Control","no-store"));
            }
        }
        verifyNoInteractions(documents);
        when(staff.requireStaffId("Bearer staff")).thenReturn("actual-staff");
        mvc.perform(multipart("/api/v1/staff/knowledge/documents/upload").file(file)
                .param("owner","spoofed").header("Authorization","Bearer staff"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(documents).upload(eq("actual-staff"),eq("ops.txt"),eq("text/plain"),any(byte[].class));
        mvc.perform(multipart("/api/v1/staff/knowledge/documents/upload")
                .file(new MockMultipartFile("file","huge.txt","text/plain",new byte[10485761]))
                .header("Authorization","Bearer staff"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(10003));
        verifyNoMoreInteractions(documents);
    }
}
