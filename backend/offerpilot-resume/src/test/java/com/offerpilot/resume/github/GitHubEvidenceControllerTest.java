package com.offerpilot.resume.github;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GitHubEvidenceControllerTest {
    @Test void settingsUseVerifiedOwnerAndNeverSerializeSubmittedCredential() throws Exception {
        var users = mock(CurrentUser.class); var service = mock(GitHubEvidenceService.class);
        when(users.require("Bearer fixture")).thenReturn("verified-owner");
        when(service.save("verified-owner","fixture","fictional_only",false)).thenReturn(
                new GitHubEvidenceService.View("fixture",true,"NOT_FETCHED","",null,null,false,null));
        var mvc = MockMvcBuilders.standaloneSetup(new GitHubEvidenceController(users,service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(put("/api/v1/resumes/github").header("Authorization","Bearer fixture").contentType("application/json")
                .content("{\"username\":\"fixture\",\"token\":\"fictional_only\",\"userId\":\"forged-owner\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.tokenConfigured").value(true)).andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fictional_only"))));
        verify(service).save("verified-owner","fixture","fictional_only",false);
        var settings = new GitHubEvidenceController.Settings(); settings.token = "fictional_only";
        org.assertj.core.api.Assertions.assertThat(settings.toString()).doesNotContain(settings.token);
    }
    @Test void unauthenticatedReadOrRefreshCannotAccessService() throws Exception {
        var users = mock(CurrentUser.class); var service = mock(GitHubEvidenceService.class);
        when(users.require(null)).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));
        var mvc = MockMvcBuilders.standaloneSetup(new GitHubEvidenceController(users,service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/resumes/github")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/api/v1/resumes/github/refresh")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
