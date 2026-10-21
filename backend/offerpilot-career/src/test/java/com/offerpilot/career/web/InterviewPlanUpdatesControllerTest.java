package com.offerpilot.career.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.career.application.*;
import com.offerpilot.common.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InterviewPlanUpdatesControllerTest {
    @Test void onlyTrustedOwnerAndExplicitValidConfirmationReachWrites() throws Exception {
        var users=mock(CurrentUser.class); var updates=mock(InterviewPlanUpdates.class);
        var plans=mock(InterviewLearningPlans.class);
        when(users.require(null)).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));
        when(users.require("Bearer token")).thenReturn("trusted");
        var mvc=MockMvcBuilders.standaloneSetup(new InterviewLearningPlanController(plans,users,updates))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String preview="/api/v1/career/learning-plans/updates/from-interview/r2";
        String confirm="/api/v1/career/learning-plans/p1/updates/from-interview/r2/confirm";
        mvc.perform(get(preview)).andExpect(status().isUnauthorized());
        mvc.perform(post(confirm).contentType("application/json").content("{\"fingerprint\":\""+"a".repeat(64)+"\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(updates);
        mvc.perform(get(preview).header("Authorization","Bearer token").param("userId","forged"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(updates).preview("trusted","r2");
        for(String invalid:java.util.List.of("{}","{\"fingerprint\":null}","{\"fingerprint\":\"bad\"}"))
            mvc.perform(post(confirm).header("Authorization","Bearer token").contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        mvc.perform(post(confirm).header("Authorization","Bearer token").contentType("application/json").content("{\"fingerprint\":\""+"a".repeat(64)+"\"}"))
                .andExpect(status().isOk());
        verify(updates).confirm("trusted","r2","p1","a".repeat(64));
        verifyNoInteractions(plans);
    }
}
