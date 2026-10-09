package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.error.GlobalExceptionHandler;
import com.offerpilot.resume.web.ResumeExportController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.ContentDisposition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResumePdfExporterTest {
    private ResumeService resumes;
    private ResumePdfRenderer renderer;
    private CurrentUser users;
    private MockMvc mvc;
    private com.fasterxml.jackson.databind.JsonNode saved;

    @BeforeEach void setup() throws Exception {
        resumes = mock(ResumeService.class);
        renderer = mock(ResumePdfRenderer.class);
        users = mock(CurrentUser.class);
        saved = new ObjectMapper().readTree(ResumeStructure.SCHEMA);
        mvc = MockMvcBuilders.standaloneSetup(new ResumeExportController(users, new ResumePdfExporter(resumes, renderer)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.require("Bearer owner")).thenReturn("owner");
        when(users.require("Bearer other")).thenReturn("other");
        when(users.require(null)).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));
        when(resumes.get("other", "rs1")).thenThrow(new BusinessException(ErrorCode.RESUME_NOT_FOUND));
    }
    private ResumeService.Resume resume(String status, boolean version) {
        var current = version ? new ResumeService.Version("v2", 2, "v1", "OPTIMIZED", saved, "stale upload text", null, null) : null;
        return new ResumeService.Resume("rs1", "林小雨/简历\r\n", status, 100, null, version ? "v2" : null, "upload.txt", null, null, current);
    }
    @Test void authenticatesAndReturnsCurrentSavedStructuredVersionAsPdfWithSafeFilename() throws Exception {
        when(resumes.get("owner", "rs1")).thenReturn(resume("READY", true));
        byte[] bytes = "%PDF-current-version".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        when(renderer.render(anyString(), same(saved), eq("classic"))).thenReturn(bytes);
        var response = mvc.perform(get("/api/v1/resumes/rs1/export.pdf").header("Authorization", "Bearer owner"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().bytes(bytes)).andReturn().getResponse();
        String header = response.getHeader("Content-Disposition");
        assertThat(header).startsWith("attachment;").contains("filename*=UTF-8''");
        assertThat(ContentDisposition.parse(header).getFilename()).isEqualTo("林小雨_简历__.pdf");
        verify(renderer).render("林小雨/简历\r\n", saved, "classic");
    }
    @Test void rejectsOtherOwnersAndMissingAuthenticationBeforeRendering() throws Exception {
        mvc.perform(get("/api/v1/resumes/rs1/export.pdf").header("Authorization", "Bearer other"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(30402));
        mvc.perform(get("/api/v1/resumes/rs1/export.pdf"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(20001));
        verifyNoInteractions(renderer);
        verify(resumes, never()).get(isNull(), anyString());
    }
    @Test void rejectsEveryNonReadyStateAndMissingVersion() throws Exception {
        for (String state : new String[]{"QUEUED", "PARSING", "FAILED", "READY"}) {
            when(resumes.get("owner", "rs1")).thenReturn(resume(state, !state.equals("READY")));
            mvc.perform(get("/api/v1/resumes/rs1/export.pdf").header("Authorization", "Bearer owner"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(30401))
                .andExpect(jsonPath("$.message").value("简历尚无可导出的已保存版本，请等待解析完成后重试"));
        }
        verifyNoInteractions(renderer);
    }
    @Test void rejectsInvalidTemplateAsValidationError() throws Exception {
        when(resumes.get("owner", "rs1")).thenReturn(resume("READY", true));
        mvc.perform(get("/api/v1/resumes/rs1/export.pdf?template=modern").header("Authorization", "Bearer owner"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10001));
        verifyNoInteractions(renderer);
    }
    @Test void exposesDiagnosableFontFailureAsBusinessError() throws Exception {
        when(resumes.get("owner", "rs1")).thenReturn(resume("READY", true));
        when(renderer.render(anyString(), any(), anyString())).thenThrow(new BusinessException(ErrorCode.RESUME_EXPORT_UNAVAILABLE));
        mvc.perform(get("/api/v1/resumes/rs1/export.pdf").header("Authorization", "Bearer owner"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50004));
    }
}
