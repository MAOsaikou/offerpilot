package com.offerpilot.career.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.career.application.SkillGapService;
import com.offerpilot.common.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SkillGapControllerTest {
    @Test void verifiesIdentityBeforeAnalysisAndPassesTheSelectedImmutableVersion() throws Exception {
        var users = mock(CurrentUser.class); var service = mock(SkillGapService.class);
        when(users.require(null)).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));
        when(users.require("Bearer fixture")).thenReturn("verified-owner");
        when(service.analyze("verified-owner",null,"job","resume","version")).thenReturn(
                new SkillGapService.Report("JOB","job","Fixture","version",List.of(),"暂无可识别技能"));
        var mvc = MockMvcBuilders.standaloneSetup(new SkillGapController(users,service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/career/skill-gap")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        mvc.perform(get("/api/v1/career/skill-gap").header("Authorization","Bearer fixture").param("jobId","job")
                .param("resumeId","resume").param("versionId","version").param("userId","forged-owner"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.resumeVersionId").value("version")).andExpect(jsonPath("$.data.skills").isEmpty());
        verify(service).analyze("verified-owner",null,"job","resume","version");
    }
}
